use crate::config::{BondingMode, TunnelConfig};
use crate::estimator::PathEstimator;
use crate::reorder::ReorderBuffer;
use crate::scheduler::{MultipathScheduler, ScheduleDecision};
use crate::telemetry::{CombinedTelemetry, LinkTelemetry, ProbeReplyPayload, RelayQuotaInfo};
use parking_lot::{Mutex, RwLock};
use std::net::{SocketAddr, ToSocketAddrs};
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
#[allow(unused_imports)]
use tracing::{debug, error, info, warn};
use velocity_proto::{
    flags, AeadCipher, Direction, PacketBufferPool, Vl01Header, HEADER_SIZE, TAG_SIZE,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ConnectionState {
    Disconnected = 0,
    Connecting = 1,
    Bonded = 2,
    Reconnecting = 3,
    Error = 4,
}

#[derive(Debug, Clone)]
pub enum TunnelEvent {
    StateChanged {
        state: ConnectionState,
        message: String,
    },
    Telemetry(CombinedTelemetry),
}

pub type TunWriterFn = Arc<dyn Fn(&[u8]) + Send + Sync>;

pub struct SocketEntry {
    pub path_id: u8,
    pub socket: Arc<UdpSocket>,
    pub link_name: String,
    pub is_metered: bool,
    pub generation: u64,
}

pub struct TunnelState {
    pub config: TunnelConfig,
    pub session_id: u64,
    pub cipher: Arc<AeadCipher>,
    pub paths: RwLock<[Option<PathEstimator>; 2]>,
    pub sockets: RwLock<[Option<SocketEntry>; 2]>,
    pub scheduler: RwLock<MultipathScheduler>,
    pub reorder_buffer: RwLock<ReorderBuffer>,
    pub pool: Arc<PacketBufferPool>,
    pub seq_counter: AtomicU64,
    pub path_gen_counter: AtomicU64,
    pub running: AtomicBool,
    pub has_ever_bonded: AtomicBool,
    pub state: RwLock<ConnectionState>,
    pub status_message: RwLock<String>,
    pub server_addr: SocketAddr,
    pub tun_fd: c_int,
    pub session_start: Instant,
    pub tun_writer: Option<TunWriterFn>,
    pub event_tx: Option<mpsc::UnboundedSender<TunnelEvent>>,
    pub relay_quota: RwLock<Option<RelayQuotaInfo>>,
}

impl TunnelState {
    /// Microseconds elapsed since `session_start`, wrapped to 32 bits for VL01 wire timestamps.
    #[inline]
    pub fn rel_time_us(&self) -> u32 {
        (self.session_start.elapsed().as_micros() & 0xFFFF_FFFF) as u32
    }

    pub fn emit_state(&self, new_state: ConnectionState, message: impl Into<String>) {
        let msg = message.into();
        let mut s = self.state.write();
        let mut m = self.status_message.write();
        if *s == new_state && *m == msg {
            return;
        }
        *s = new_state;
        *m = msg.clone();
        if new_state == ConnectionState::Bonded {
            self.has_ever_bonded.store(true, Ordering::SeqCst);
        }
        if let Some(tx) = &self.event_tx {
            let _ = tx.send(TunnelEvent::StateChanged {
                state: new_state,
                message: msg,
            });
        }
    }

    pub fn write_to_tun(&self, packet: &[u8]) {
        if let Some(writer) = &self.tun_writer {
            writer(packet);
            return;
        }
        #[cfg(unix)]
        if self.tun_fd >= 0 {
            unsafe {
                let _ = libc::write(
                    self.tun_fd,
                    packet.as_ptr() as *const libc::c_void,
                    packet.len(),
                );
            }
        }
    }
}

pub struct TunnelRuntime {
    pub state: Arc<TunnelState>,
    tun_reader_handle: Mutex<Option<JoinHandle<()>>>,
    housekeeping_started: AtomicBool,
    shutdown_tx: tokio::sync::broadcast::Sender<()>,
}

/// Resolve host:port string supporting both IP literals and domain names, preferring IPv4.
pub fn resolve_server_addr(host: &str, port: u16) -> Result<SocketAddr, String> {
    let trimmed = host.trim();
    if trimmed.is_empty() {
        return Err("Relay server host cannot be empty".to_string());
    }
    if port == 0 {
        return Err("Relay server port must be in 1..=65535".to_string());
    }
    let host_port = if trimmed.contains(':') && !trimmed.starts_with('[') {
        format!("[{}]:{}", trimmed, port)
    } else {
        format!("{}:{}", trimmed, port)
    };
    let addrs: Vec<SocketAddr> = host_port
        .to_socket_addrs()
        .map_err(|e| format!("Failed to resolve relay '{}': {}", trimmed, e))?
        .collect();
    addrs
        .iter()
        .find(|a| a.is_ipv4())
        .copied()
        .or_else(|| addrs.into_iter().next())
        .ok_or_else(|| format!("No IP address resolved for relay '{}'", trimmed))
}

/// Calculate standard 16-bit Internet Checksum.
fn ip_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    for chunk in data.chunks(2) {
        let word = if chunk.len() == 2 {
            u16::from_be_bytes([chunk[0], chunk[1]])
        } else {
            u16::from_be_bytes([chunk[0], 0])
        };
        sum = sum.wrapping_add(word as u32);
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !sum as u16
}

/// Synthesize a raw IPv4 UDP packet.
fn craft_ipv4_udp_packet(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let total_len = 20 + 8 + payload.len();
    let mut packet = vec![0u8; total_len];

    packet[0] = 0x45;
    packet[1] = 0x00;
    packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    packet[4..6].copy_from_slice(&0x5432u16.to_be_bytes());
    packet[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
    packet[8] = 64;
    packet[9] = 17;
    packet[10..12].copy_from_slice(&0u16.to_be_bytes());
    packet[12..16].copy_from_slice(&src_ip);
    packet[16..20].copy_from_slice(&dst_ip);

    let cksum = ip_checksum(&packet[..20]);
    packet[10..12].copy_from_slice(&cksum.to_be_bytes());

    let udp_len = (8 + payload.len()) as u16;
    packet[20..22].copy_from_slice(&src_port.to_be_bytes());
    packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
    packet[24..26].copy_from_slice(&udp_len.to_be_bytes());
    packet[26..28].copy_from_slice(&0u16.to_be_bytes());

    packet[28..].copy_from_slice(payload);
    packet
}

/// Fallback DNS resolver while tunnel handshake is establishing.
fn resolve_dns_direct(
    state: Arc<TunnelState>,
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    dns_query: Vec<u8>,
) {
    std::thread::spawn(move || {
        let sock = match std::net::UdpSocket::bind("0.0.0.0:0") {
            Ok(s) => s,
            Err(_) => return,
        };
        let _ = sock.set_read_timeout(Some(Duration::from_millis(1200)));

        let upstreams = ["1.1.1.1:53", "8.8.8.8:53"];
        for upstream in &upstreams {
            if !state.running.load(Ordering::Relaxed) {
                return;
            }
            if sock.send_to(&dns_query, upstream).is_ok() {
                let mut resp_buf = [0u8; 2048];
                if let Ok((resp_len, _)) = sock.recv_from(&mut resp_buf) {
                    let resp_pkt = craft_ipv4_udp_packet(
                        dst_ip,
                        src_ip,
                        dst_port,
                        src_port,
                        &resp_buf[..resp_len],
                    );
                    state.write_to_tun(&resp_pkt);
                    return;
                }
            }
        }
    });
}

impl TunnelRuntime {
    pub fn new(tun_fd: c_int, config: TunnelConfig) -> Result<Self, String> {
        Self::new_with_options(tun_fd, config, None, None)
    }

    pub fn new_with_options(
        tun_fd: c_int,
        config: TunnelConfig,
        event_tx: Option<mpsc::UnboundedSender<TunnelEvent>>,
        tun_writer: Option<TunWriterFn>,
    ) -> Result<Self, String> {
        if config.psk.trim().is_empty() {
            return Err("Relay pre-shared key cannot be empty".to_string());
        }

        let server_addr = resolve_server_addr(&config.server_host, config.server_port)?;

        let session_id = config.session_id.unwrap_or_else(|| {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos();
            (now as u64) ^ 0xA5A5_5A5A_1234_5678
        });

        let cipher = Arc::new(
            AeadCipher::from_psk(config.psk.as_bytes(), session_id)
                .map_err(|e| format!("Failed to derive AEAD key: {:?}", e))?,
        );

        let pool = PacketBufferPool::new(128, 1024);
        let mode = BondingMode::from(config.mode);
        let mut scheduler = MultipathScheduler::new(mode);
        scheduler.update_tuning(config.wifi_bias_percent, config.fec_enabled);

        #[cfg(unix)]
        let safe_tun_fd = if tun_fd >= 0 {
            let duped = unsafe { libc::dup(tun_fd) };
            if duped >= 0 {
                unsafe {
                    let flags_val = libc::fcntl(duped, libc::F_GETFL, 0);
                    if flags_val >= 0 {
                        let _ = libc::fcntl(duped, libc::F_SETFL, flags_val | libc::O_NONBLOCK);
                    }
                }
            }
            duped
        } else {
            -1
        };
        #[cfg(not(unix))]
        let safe_tun_fd = tun_fd;

        let state = Arc::new(TunnelState {
            config,
            session_id,
            cipher,
            paths: RwLock::new([None, None]),
            sockets: RwLock::new([None, None]),
            scheduler: RwLock::new(scheduler),
            reorder_buffer: RwLock::new(ReorderBuffer::new(2048)),
            pool,
            seq_counter: AtomicU64::new(1),
            path_gen_counter: AtomicU64::new(1),
            running: AtomicBool::new(true),
            has_ever_bonded: AtomicBool::new(false),
            state: RwLock::new(ConnectionState::Connecting),
            status_message: RwLock::new("Connecting to relay...".to_string()),
            server_addr,
            tun_fd: safe_tun_fd,
            session_start: Instant::now(),
            tun_writer,
            event_tx,
            relay_quota: RwLock::new(None),
        });

        let (shutdown_tx, _) = tokio::sync::broadcast::channel(4);

        let tun_reader_handle = if safe_tun_fd >= 0 {
            Some(Self::spawn_tun_reader(Arc::clone(&state)))
        } else {
            None
        };

        Ok(Self {
            state,
            tun_reader_handle: Mutex::new(tun_reader_handle),
            housekeeping_started: AtomicBool::new(false),
            shutdown_tx,
        })
    }

    /// Start the 200ms housekeeping watchdog & telemetry publisher inside the active Tokio runtime.
    pub fn start_housekeeping(&self) {
        if self.housekeeping_started.swap(true, Ordering::SeqCst) {
            return;
        }
        let state = Arc::clone(&self.state);
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(200));
            let mut handshake_elapsed_ticks: u32 = 0;

            while state.running.load(Ordering::Relaxed) {
                tokio::select! {
                    _ = shutdown_rx.recv() => break,
                    _ = interval.tick() => {}
                }
                if !state.running.load(Ordering::Relaxed) {
                    break;
                }

                // 1. Sample sliding-window throughput & probe timeout liveness per path
                let (rtt0_us, rtt1_us, registered_count, alive_count, verified_count, p0_alive, p1_alive) = {
                    let mut paths = state.paths.write();
                    let mut reg = 0usize;
                    let mut alive = 0usize;
                    let mut verified = 0usize;
                    let mut p0_ok = false;
                    let mut p1_ok = false;
                    let mut r0 = 20_000u32;
                    let mut r1 = 40_000u32;

                    for idx in 0..2 {
                        if let Some(p) = &mut paths[idx] {
                            reg += 1;
                            p.check_probe_timeout();
                            p.sample_rates();
                            if p.is_alive() {
                                alive += 1;
                            }
                            if p.has_verified_path() {
                                verified += 1;
                                if idx == 0 {
                                    p0_ok = true;
                                } else {
                                    p1_ok = true;
                                }
                            }
                            if idx == 0 {
                                r0 = p.srtt_us;
                            } else {
                                r1 = p.srtt_us;
                            }
                        }
                    }
                    (r0, r1, reg, alive, verified, p0_ok, p1_ok)
                };

                // 2. Flush any timed-out reorder gaps so streams never stall permanently
                let flushed = {
                    let mut reorder = state.reorder_buffer.write();
                    reorder.check_timeouts(rtt0_us, rtt1_us)
                };
                for pkt in flushed {
                    state.write_to_tun(&pkt);
                }

                // 3. Reconcile honest connection state
                if registered_count == 0 {
                    handshake_elapsed_ticks = 0;
                    if state.has_ever_bonded.load(Ordering::Relaxed) {
                        state.emit_state(ConnectionState::Reconnecting, "Waiting for network...");
                    } else {
                        state.emit_state(ConnectionState::Connecting, "Waiting for internet path...");
                    }
                } else if verified_count > 0 {
                    handshake_elapsed_ticks = 0;
                    let msg = match (p0_alive, p1_alive) {
                        (true, true) => "Bonded · Wi-Fi + Cellular",
                        (true, false) => "Connected · Wi-Fi only",
                        (false, true) => "Connected · Cellular only",
                        (false, false) => "Reconnecting...",
                    };
                    state.emit_state(ConnectionState::Bonded, msg);
                } else if alive_count > 0 {
                    handshake_elapsed_ticks += 1;
                    if state.has_ever_bonded.load(Ordering::Relaxed) {
                        state.emit_state(ConnectionState::Reconnecting, "Reconnecting to relay...");
                    } else if handshake_elapsed_ticks >= 25 {
                        state.emit_state(
                            ConnectionState::Reconnecting,
                            "Relay not responding — retrying...",
                        );
                    } else {
                        state.emit_state(ConnectionState::Connecting, "Verifying relay handshake...");
                    }
                } else {
                    state.emit_state(ConnectionState::Reconnecting, "Connection interrupted, retrying...");
                }

                // 4. Push telemetry snapshot to observer
                if let Some(tx) = &state.event_tx {
                    let telem = Self::build_telemetry(&state);
                    let _ = tx.send(TunnelEvent::Telemetry(telem));
                }
            }
        });
    }

    /// Background thread continuously reading IP packets from the TUN device with clean non-blocking poll.
    fn spawn_tun_reader(state: Arc<TunnelState>) -> JoinHandle<()> {
        std::thread::Builder::new()
            .name("velocity-tun-reader".to_string())
            .spawn(move || {
                #[allow(unused_mut)]
                let mut buf = [0u8; 2048];
                #[allow(unused_variables)]
                let tun_fd = state.tun_fd;

                while state.running.load(Ordering::Relaxed) {
                    #[cfg(unix)]
                    let n: isize = {
                        let mut pfd = libc::pollfd {
                            fd: tun_fd,
                            events: libc::POLLIN,
                            revents: 0,
                        };
                        let ready = unsafe { libc::poll(&mut pfd, 1, 50) };
                        if ready <= 0 {
                            continue;
                        }
                        if (pfd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL)) != 0 {
                            break;
                        }
                        unsafe {
                            libc::read(tun_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len())
                        }
                    };
                    #[cfg(not(unix))]
                    let n: isize = {
                        std::thread::sleep(Duration::from_millis(50));
                        -1
                    };

                    if n <= 0 {
                        continue;
                    }

                    let pkt_len = n as usize;
                    Self::handle_outbound_tun_packet(&state, &buf[..pkt_len]);
                }

                #[cfg(unix)]
                if tun_fd >= 0 {
                    unsafe {
                        libc::close(tun_fd);
                    }
                }
            })
            .expect("Failed to spawn TUN reader thread")
    }

    /// Process a single IP packet coming from the OS TUN (or injected by tests).
    pub fn inject_outbound_packet(&self, packet: &[u8]) {
        Self::handle_outbound_tun_packet(&self.state, packet);
    }

    fn handle_outbound_tun_packet(state: &Arc<TunnelState>, packet: &[u8]) {
        let pkt_len = packet.len();
        // Direct DNS guard fallback only while tunnel is not yet bonded
        if pkt_len >= 20 && (packet[0] >> 4) == 4 {
            let ihl = ((packet[0] & 0x0F) * 4) as usize;
            let protocol = packet[9];

            if protocol == 17 && pkt_len >= ihl + 8 && ihl >= 20 {
                let src_port = u16::from_be_bytes([packet[ihl], packet[ihl + 1]]);
                let dst_port = u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]);

                if dst_port == 53 && state.config.direct_dns_guard {
                    let is_bonded = *state.state.read() == ConnectionState::Bonded;
                    if !is_bonded {
                        let mut src_ip = [0u8; 4];
                        let mut dst_ip = [0u8; 4];
                        src_ip.copy_from_slice(&packet[12..16]);
                        dst_ip.copy_from_slice(&packet[16..20]);

                        let udp_len =
                            u16::from_be_bytes([packet[ihl + 4], packet[ihl + 5]]) as usize;
                        let end = (ihl + udp_len).min(pkt_len);
                        if end > ihl + 8 {
                            let dns_payload = packet[ihl + 8..end].to_vec();
                            resolve_dns_direct(
                                Arc::clone(state),
                                src_ip,
                                dst_ip,
                                src_port,
                                dst_port,
                                dns_payload,
                            );
                        }
                        return;
                    }
                }
            }
        }

        if *state.state.read() == ConnectionState::Bonded {
            Self::forward_tun_packet(state, packet);
        }
    }

    /// Schedule and transmit an encrypted TUN packet over the active multipath sockets.
    fn forward_tun_packet(state: &TunnelState, packet: &[u8]) {
        if packet.len() + HEADER_SIZE + TAG_SIZE > 2048 {
            return;
        }
        let seq = state.seq_counter.fetch_add(1, Ordering::SeqCst);
        let decision = {
            let paths = state.paths.read();
            state.scheduler.write().schedule(packet, seq, &paths)
        };

        let tx_time_us = state.rel_time_us();

        let send_frame = |path_id: u8, frame_flags: u8, frame_seq: u64, payload: &[u8]| {
            let sock_opt = {
                let sockets = state.sockets.read();
                sockets[path_id as usize].as_ref().map(|e| Arc::clone(&e.socket))
            };
            let Some(sock) = sock_opt else {
                return;
            };

            let mut header = Vl01Header::new(frame_flags, path_id, state.session_id, frame_seq);
            header.tx_timestamp_us = tx_time_us;

            let mut out = [0u8; 2048];
            let mut hdr_copy = [0u8; HEADER_SIZE];
            if header.encode(&mut hdr_copy).is_err() {
                return;
            }
            out[..HEADER_SIZE].copy_from_slice(&hdr_copy);
            out[HEADER_SIZE..HEADER_SIZE + payload.len()].copy_from_slice(payload);

            if let Ok(enc_len) = state.cipher.encrypt_in_place(
                Direction::ClientToServer,
                path_id,
                frame_seq,
                &hdr_copy,
                &mut out[HEADER_SIZE..],
                payload.len(),
            ) {
                let total_len = HEADER_SIZE + enc_len;
                if sock.try_send_to(&out[..total_len], state.server_addr).is_ok() {
                    let mut paths = state.paths.write();
                    if let Some(p) = &mut paths[path_id as usize] {
                        p.record_sent(payload.len());
                    }
                }
            }
        };

        match decision {
            ScheduleDecision::SendSingle { path_id } => {
                send_frame(path_id, flags::DATA, seq, packet);
            }
            ScheduleDecision::SendDuplicate {
                primary_path,
                secondary_path,
            } => {
                send_frame(primary_path, flags::DATA | flags::DUPLICATE, seq, packet);
                send_frame(secondary_path, flags::DATA | flags::DUPLICATE, seq, packet);
            }
            ScheduleDecision::SendWithFec {
                path_id,
                parity_data,
                block_base_seq,
            } => {
                send_frame(path_id, flags::DATA, seq, packet);
                if parity_data.len() + HEADER_SIZE + TAG_SIZE <= 2048 {
                    send_frame(path_id, flags::FEC_PARITY, block_base_seq, &parity_data);
                }
            }
        }
    }

    pub fn set_mode(&self, mode: BondingMode) {
        self.state.scheduler.write().set_mode(mode);
    }

    pub fn update_tuning(&self, wifi_bias_percent: u8, fec_enabled: bool) {
        self.state
            .scheduler
            .write()
            .update_tuning(wifi_bias_percent, fec_enabled);
    }

    fn is_path_current(st: &TunnelState, path_id: u8, gen: u64) -> bool {
        let sockets = st.sockets.read();
        sockets[path_id as usize]
            .as_ref()
            .map(|s| s.generation == gen)
            .unwrap_or(false)
    }

    pub fn register_socket(
        &self,
        path_id: u8,
        socket: UdpSocket,
        link_name: String,
        is_metered: bool,
    ) -> bool {
        if path_id > 1 {
            return false;
        }

        self.start_housekeeping();
        let sock_arc = Arc::new(socket);
        let gen = self.state.path_gen_counter.fetch_add(1, Ordering::SeqCst);

        {
            let mut paths = self.state.paths.write();
            let mut sockets = self.state.sockets.write();

            paths[path_id as usize] = Some(PathEstimator::new(path_id, link_name.clone(), is_metered));
            sockets[path_id as usize] = Some(SocketEntry {
                path_id,
                socket: Arc::clone(&sock_arc),
                link_name,
                is_metered,
                generation: gen,
            });
        }

        // Receiver task for this path
        let state_rx = Arc::clone(&self.state);
        let sock_rx = Arc::clone(&sock_arc);
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            while state_rx.running.load(Ordering::Relaxed)
                && Self::is_path_current(&state_rx, path_id, gen)
            {
                let recv_res = tokio::select! {
                    _ = shutdown_rx.recv() => break,
                    res = sock_rx.recv_from(&mut buf) => res,
                };

                let (len, _) = match recv_res {
                    Ok(r) => r,
                    Err(_) => {
                        tokio::time::sleep(Duration::from_millis(10)).await;
                        continue;
                    }
                };

                if len < HEADER_SIZE + TAG_SIZE {
                    continue;
                }

                let header = match Vl01Header::decode(&buf[..HEADER_SIZE]) {
                    Ok(h) => h,
                    Err(_) => continue,
                };

                if header.session_id != state_rx.session_id {
                    continue;
                }

                let mut header_copy = [0u8; HEADER_SIZE];
                header_copy.copy_from_slice(&buf[..HEADER_SIZE]);

                let plain_len = match state_rx.cipher.decrypt_in_place(
                    Direction::ServerToClient,
                    header.path_id,
                    header.seq_num,
                    &header_copy,
                    &mut buf[HEADER_SIZE..len],
                    len - HEADER_SIZE,
                ) {
                    Ok(l) => l,
                    Err(_) => continue,
                };

                // Record RTT & path liveness on every authenticated reply
                let now_rel = state_rx.rel_time_us();
                {
                    let mut paths = state_rx.paths.write();
                    if let Some(p) = &mut paths[path_id as usize] {
                        p.record_rx(plain_len);
                        if header.tx_timestamp_us != 0 {
                            let rtt_us = now_rel.wrapping_sub(header.tx_timestamp_us);
                            p.record_rtt_sample(rtt_us.max(1));
                        }
                        if header.ack_base > 0 {
                            p.record_ack(plain_len.max(64), p.srtt_us as u64);
                        }
                    }
                }

                // Transition to Bonded only after real authenticated server packet
                if *state_rx.state.read() != ConnectionState::Bonded {
                    state_rx.emit_state(ConnectionState::Bonded, "Multipath relay connected");
                }

                if header.is_control_probe() || header.is_keepalive() || header.is_speedtest() {
                    if (header.is_control_probe() || header.is_keepalive()) && plain_len > 2 {
                        let body = &buf[HEADER_SIZE..HEADER_SIZE + plain_len];
                        if body.first() == Some(&b'{') {
                            if let Ok(parsed) = serde_json::from_slice::<ProbeReplyPayload>(body) {
                                if let Some(q) = parsed.quota {
                                    *state_rx.relay_quota.write() = Some(q);
                                }
                            }
                        }
                    }
                    continue;
                }

                if header.is_data() || header.is_fec_parity() {
                    let (r0, r1) = {
                        let paths = state_rx.paths.read();
                        (
                            paths[0].as_ref().map(|p| p.srtt_us).unwrap_or(20_000),
                            paths[1].as_ref().map(|p| p.srtt_us).unwrap_or(40_000),
                        )
                    };
                    let ready_pkts = {
                        let mut reorder = state_rx.reorder_buffer.write();
                        reorder.push(
                            header.seq_num,
                            header.is_fec_parity(),
                            header.is_duplicate(),
                            &buf[HEADER_SIZE..HEADER_SIZE + plain_len],
                            r0,
                            r1,
                        )
                    };

                    for pkt in ready_pkts {
                        state_rx.write_to_tun(&pkt);
                    }
                }
            }
        });

        // Periodic probe / keepalive loop (250ms cadence)
        let state_probe = Arc::clone(&self.state);
        let sock_probe = Arc::clone(&sock_arc);
        let mut shutdown_probe = self.shutdown_tx.subscribe();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(250));
            while state_probe.running.load(Ordering::Relaxed)
                && Self::is_path_current(&state_probe, path_id, gen)
            {
                tokio::select! {
                    _ = shutdown_probe.recv() => break,
                    _ = interval.tick() => {}
                }
                if !state_probe.running.load(Ordering::Relaxed)
                    || !Self::is_path_current(&state_probe, path_id, gen)
                {
                    break;
                }

                let tx_time_us = state_probe.rel_time_us();
                let seq = state_probe.seq_counter.fetch_add(1, Ordering::SeqCst);

                let mut header =
                    Vl01Header::new(flags::CONTROL_PROBE, path_id, state_probe.session_id, seq);
                header.tx_timestamp_us = tx_time_us;

                let dummy = b"PING";
                let mut out = [0u8; HEADER_SIZE + 4 + TAG_SIZE];
                let mut hdr = [0u8; HEADER_SIZE];
                if header.encode(&mut hdr).is_ok() {
                    out[..HEADER_SIZE].copy_from_slice(&hdr);
                    out[HEADER_SIZE..HEADER_SIZE + dummy.len()].copy_from_slice(dummy);

                    if let Ok(enc_len) = state_probe.cipher.encrypt_in_place(
                        Direction::ClientToServer,
                        path_id,
                        seq,
                        &hdr,
                        &mut out[HEADER_SIZE..],
                        dummy.len(),
                    ) {
                        let total_len = HEADER_SIZE + enc_len;
                        if sock_probe.send_to(&out[..total_len], state_probe.server_addr).await.is_ok() {
                            let mut paths = state_probe.paths.write();
                            if let Some(p) = &mut paths[path_id as usize] {
                                p.mark_probe_sent();
                            }
                        }
                    }
                }
            }
        });

        true
    }

    pub fn unregister_socket(&self, path_id: u8) {
        if path_id > 1 {
            return;
        }
        {
            let mut paths = self.state.paths.write();
            let mut sockets = self.state.sockets.write();
            paths[path_id as usize] = None;
            sockets[path_id as usize] = None;
        }

        let remaining_verified = {
            let paths = self.state.paths.read();
            paths.iter().flatten().filter(|p| p.has_verified_path()).count()
        };

        if remaining_verified == 0 {
            if self.state.has_ever_bonded.load(Ordering::Relaxed) {
                self.state
                    .emit_state(ConnectionState::Reconnecting, "Path lost, searching for link...");
            } else {
                self.state
                    .emit_state(ConnectionState::Connecting, "Waiting for validated network...");
            }
        }
    }

    pub fn update_link_metrics(&self, path_id: u8, rssi_dbm: i32, link_speed_mbps: i32) {
        if path_id > 1 {
            return;
        }
        let mut paths = self.state.paths.write();
        if let Some(path) = &mut paths[path_id as usize] {
            path.rssi_dbm = rssi_dbm;
            path.link_speed_mbps = link_speed_mbps;
        }
    }

    pub fn build_telemetry(state: &TunnelState) -> CombinedTelemetry {
        let mut paths = state.paths.write();
        let reorder = state.reorder_buffer.read();
        let scheduler = state.scheduler.read();
        let conn_state = *state.state.read();
        let status_msg = state.status_message.read().clone();

        let sched0 = paths[0].as_ref().map(|p| p.window_bytes_scheduled).unwrap_or(0);
        let sched1 = paths[1].as_ref().map(|p| p.window_bytes_scheduled).unwrap_or(0);
        let sched_total = sched0 + sched1;

        let (weight0, weight1) = if sched_total > 0 {
            let w0 = ((sched0 as f64 / sched_total as f64) * 100.0).round() as i32;
            (w0, 100 - w0)
        } else {
            let a0 = paths[0].as_ref().map(|p| p.has_verified_path()).unwrap_or(false);
            let a1 = paths[1].as_ref().map(|p| p.has_verified_path()).unwrap_or(false);
            match (a0, a1) {
                (true, true) => (50, 50),
                (true, false) => (100, 0),
                (false, true) => (0, 100),
                (false, false) => (0, 0),
            }
        };

        for p in paths.iter_mut().flatten() {
            p.window_bytes_scheduled = 0;
        }

        let make_link = |opt: &Option<PathEstimator>, path_id: i32, kind: &str, iface: &str, metered: bool, weight: i32| {
            opt.as_ref()
                .map(|p| LinkTelemetry {
                    path_id,
                    link_type: kind.to_string(),
                    interface_name: p.name.clone(),
                    active: p.has_verified_path(),
                    has_internet: p.has_verified_path(),
                    is_metered: p.is_metered,
                    download_mbps: p.rx_mbps,
                    upload_mbps: p.tx_mbps,
                    rtt_ms: if p.has_verified_path() { (p.srtt_us as f64) / 1000.0 } else { 0.0 },
                    min_rtt_ms: if p.has_verified_path() { (p.min_rtt_us as f64) / 1000.0 } else { 0.0 },
                    jitter_ms: if p.has_verified_path() { (p.jitter_us as f64) / 1000.0 } else { 0.0 },
                    loss_percent: (p.loss_rate as f64) * 100.0,
                    scheduler_weight_percent: weight,
                    signal_dbm: p.rssi_dbm,
                    link_speed_mbps: p.link_speed_mbps,
                    bytes_rx: p.bytes_rx,
                    bytes_tx: p.bytes_sent,
                })
                .unwrap_or_else(|| LinkTelemetry {
                    path_id,
                    link_type: kind.to_string(),
                    interface_name: iface.to_string(),
                    active: false,
                    has_internet: false,
                    is_metered: metered,
                    download_mbps: 0.0,
                    upload_mbps: 0.0,
                    rtt_ms: 0.0,
                    min_rtt_ms: 0.0,
                    jitter_ms: 0.0,
                    loss_percent: 0.0,
                    scheduler_weight_percent: 0,
                    signal_dbm: -120,
                    link_speed_mbps: 0,
                    bytes_rx: 0,
                    bytes_tx: 0,
                })
        };

        let wifi_link = make_link(&paths[0], 0, "WIFI", "wlan0", false, weight0);
        let cell_link = make_link(&paths[1], 1, "CELLULAR", "rmnet0", true, weight1);

        let combined_down = wifi_link.download_mbps + cell_link.download_mbps;
        let combined_up = wifi_link.upload_mbps + cell_link.upload_mbps;
        let avg_rtt = match (wifi_link.active, cell_link.active) {
            (true, true) => (wifi_link.rtt_ms + cell_link.rtt_ms) / 2.0,
            (true, false) => wifi_link.rtt_ms,
            (false, true) => cell_link.rtt_ms,
            (false, false) => 0.0,
        };

        let efficiency = if wifi_link.active && cell_link.active && combined_down > 0.05 {
            let max_single = wifi_link.download_mbps.max(cell_link.download_mbps).max(0.01);
            ((combined_down / (wifi_link.download_mbps + cell_link.download_mbps).max(max_single)) * 100.0)
                .clamp(0.0, 100.0) as i32
        } else if wifi_link.active || cell_link.active {
            100
        } else {
            0
        };

        let rtt0_us = paths[0].as_ref().map(|p| p.srtt_us).unwrap_or(20_000);
        let rtt1_us = paths[1].as_ref().map(|p| p.srtt_us).unwrap_or(40_000);
        let reorder_timeout_ms = ReorderBuffer::adaptive_timeout(rtt0_us, rtt1_us).as_millis() as u64;

        let session_bytes_rx = wifi_link.bytes_rx + cell_link.bytes_rx;
        let session_bytes_tx = wifi_link.bytes_tx + cell_link.bytes_tx;
        let cellular_bytes_used_mb = (cell_link.bytes_rx + cell_link.bytes_tx) as f64 / (1024.0 * 1024.0);

        let relay_quota = state.relay_quota.read().clone();

        CombinedTelemetry {
            timestamp_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            wifi: wifi_link,
            cellular: cell_link,
            combined_download_mbps: combined_down,
            combined_upload_mbps: combined_up,
            effective_rtt_ms: avg_rtt,
            aggregation_efficiency_percent: efficiency,
            reorder_buffer_depth: reorder.reordered_packets as usize,
            reorder_timeout_ms,
            fec_recovered_packets: reorder.fec_recovered_packets,
            fec_sent_packets: scheduler.fec_sent_packets,
            duplicated_packets: scheduler.duplicated_packets,
            session_bytes_rx,
            session_bytes_tx,
            cellular_bytes_used_mb,
            active_mode: scheduler.mode as i32,
            connection_state: conn_state as i32,
            status_message: status_msg,
            relay_quota,
        }
    }

    pub fn get_telemetry(&self) -> CombinedTelemetry {
        Self::build_telemetry(&self.state)
    }

    pub fn get_telemetry_json(&self) -> String {
        let telem = self.get_telemetry();
        serde_json::to_string(&telem).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn stop(&self) {
        self.state.running.store(false, Ordering::SeqCst);
        self.state.emit_state(ConnectionState::Disconnected, "Disconnected");
        let _ = self.shutdown_tx.send(());
        if let Some(handle) = self.tun_reader_handle.lock().take() {
            let _ = handle.join();
        }
    }
}

#[derive(Debug, Clone)]
pub struct ProbeOutcome {
    pub rtt_ms: f64,
    pub quota: Option<RelayQuotaInfo>,
}

/// Perform a single encrypted VL01 UDP probe against a relay server, returning RTT in ms.
pub async fn probe_server_udp(
    host: &str,
    port: u16,
    psk: &str,
    path_id: u8,
    prebound_socket: Option<UdpSocket>,
    timeout_ms: u64,
) -> Result<f64, String> {
    probe_server_udp_detailed(host, port, psk, path_id, prebound_socket, timeout_ms)
        .await
        .map(|o| o.rtt_ms)
}

/// Perform an encrypted VL01 probe returning both RTT (ms) and optional global relay monthly quota.
pub async fn probe_server_udp_detailed(
    host: &str,
    port: u16,
    psk: &str,
    path_id: u8,
    prebound_socket: Option<UdpSocket>,
    timeout_ms: u64,
) -> Result<ProbeOutcome, String> {
    if psk.trim().is_empty() {
        return Err("Relay pre-shared key cannot be empty".to_string());
    }
    let server_addr = resolve_server_addr(host, port)?;
    let session_id = 0x5052_4F42_4500_0001u64 ^ (path_id as u64);
    let cipher = AeadCipher::from_psk(psk.as_bytes(), session_id)
        .map_err(|e| format!("Crypto error: {:?}", e))?;

    let sock = match prebound_socket {
        Some(s) => s,
        None => UdpSocket::bind("0.0.0.0:0")
            .await
            .map_err(|e| format!("Bind failed: {}", e))?,
    };

    let start = Instant::now();
    let tx_us = 1u32;
    let mut hdr = Vl01Header::new(flags::CONTROL_PROBE, path_id, session_id, 1);
    hdr.tx_timestamp_us = tx_us;

    let payload = b"PROBE";
    let mut packet = [0u8; HEADER_SIZE + 5 + TAG_SIZE];
    let mut hdr_bytes = [0u8; HEADER_SIZE];
    hdr.encode(&mut hdr_bytes).unwrap();
    packet[..HEADER_SIZE].copy_from_slice(&hdr_bytes);
    packet[HEADER_SIZE..HEADER_SIZE + payload.len()].copy_from_slice(payload);

    let enc_len = cipher
        .encrypt_in_place(
            Direction::ClientToServer,
            path_id,
            1,
            &hdr_bytes,
            &mut packet[HEADER_SIZE..],
            payload.len(),
        )
        .map_err(|e| format!("Encrypt error: {:?}", e))?;

    sock.send_to(&packet[..HEADER_SIZE + enc_len], server_addr)
        .await
        .map_err(|e| format!("Send failed: {}", e))?;

    let mut rx_buf = [0u8; 1024];
    let recv = tokio::time::timeout(Duration::from_millis(timeout_ms), sock.recv_from(&mut rx_buf))
        .await
        .map_err(|_| "Timed out waiting for relay response".to_string())?
        .map_err(|e| format!("Receive failed: {}", e))?;

    let (rx_len, _) = recv;
    if rx_len < HEADER_SIZE + TAG_SIZE {
        return Err("Invalid response size".to_string());
    }
    let rx_hdr = Vl01Header::decode(&rx_buf[..HEADER_SIZE])
        .map_err(|e| format!("Invalid VL01 header: {:?}", e))?;
    if rx_hdr.session_id != session_id {
        return Err("Unexpected session ID".to_string());
    }
    let mut rx_hdr_bytes = [0u8; HEADER_SIZE];
    rx_hdr_bytes.copy_from_slice(&rx_buf[..HEADER_SIZE]);
    let plain_len = cipher
        .decrypt_in_place(
            Direction::ServerToClient,
            rx_hdr.path_id,
            rx_hdr.seq_num,
            &rx_hdr_bytes,
            &mut rx_buf[HEADER_SIZE..rx_len],
            rx_len - HEADER_SIZE,
        )
        .map_err(|_| "Relay authentication failed (key mismatch)".to_string())?;

    let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
    let body = &rx_buf[HEADER_SIZE..HEADER_SIZE + plain_len];
    let quota = if body.first() == Some(&b'{') {
        serde_json::from_slice::<ProbeReplyPayload>(body)
            .ok()
            .and_then(|p| p.quota)
    } else {
        None
    };

    Ok(ProbeOutcome {
        rtt_ms: elapsed_ms,
        quota,
    })
}
