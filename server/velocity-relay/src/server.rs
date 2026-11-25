use crate::quota::{QuotaSnapshot, QuotaTracker, DEFAULT_ORACLE_LIMIT_GB};
use crate::session::ClientSession;
use crate::tun::{TunReader, TunWriter};
use parking_lot::{Mutex, RwLock};
use serde::Serialize;
use socket2::SockRef;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::Notify;
use tracing::{debug, error, info, warn};
use velocity_proto::{
    flags, AeadCipher, Direction, PacketBufferPool, Vl01Header, HEADER_SIZE, TAG_SIZE,
};

const UDP_SOCKET_BUF_BYTES: usize = 16 * 1024 * 1024;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProbeReplyPayload {
    ok: bool,
    quota: QuotaSnapshot,
}

pub struct RelayServer {
    pub bind_addr: SocketAddr,
    pub psk: String,
    pub sessions: Arc<RwLock<HashMap<u64, Arc<ClientSession>>>>,
    pub ip_routes: Arc<RwLock<HashMap<[u8; 4], u64>>>,
    pub running: Arc<AtomicBool>,
    pub shutdown_notify: Arc<Notify>,
    pub pool: Arc<PacketBufferPool>,
    pub quota: Arc<QuotaTracker>,
    pub tun_reader: Option<Arc<Mutex<Box<dyn TunReader>>>>,
    pub tun_writer: Option<Arc<dyn TunWriter>>,
}

impl RelayServer {
    pub fn new(bind_addr: SocketAddr, psk: String) -> Self {
        let quota = Arc::new(QuotaTracker::new(
            "Oracle Cloud Free".to_string(),
            DEFAULT_ORACLE_LIMIT_GB,
            false,
            None,
        ));
        Self::new_with_quota(bind_addr, psk, quota)
    }

    pub fn new_with_quota(
        bind_addr: SocketAddr,
        psk: String,
        quota: Arc<QuotaTracker>,
    ) -> Self {
        Self {
            bind_addr,
            psk,
            sessions: Arc::new(RwLock::new(HashMap::new())),
            ip_routes: Arc::new(RwLock::new(HashMap::new())),
            running: Arc::new(AtomicBool::new(true)),
            shutdown_notify: Arc::new(Notify::new()),
            pool: PacketBufferPool::new(128, 2048),
            quota,
            tun_reader: None,
            tun_writer: None,
        }
    }

    pub fn with_split_tun(
        mut self,
        reader: Box<dyn TunReader>,
        writer: Arc<dyn TunWriter>,
    ) -> Self {
        self.tun_reader = Some(Arc::new(Mutex::new(reader)));
        self.tun_writer = Some(writer);
        self
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        self.quota.persist();
        self.shutdown_notify.notify_waiters();
    }

    pub async fn run(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let socket = Arc::new(UdpSocket::bind(self.bind_addr).await?);
        self.run_with_bound_socket(socket).await
    }

    fn tune_udp_socket(socket: &UdpSocket) {
        let sock_ref = SockRef::from(socket);
        if let Err(e) = sock_ref.set_recv_buffer_size(UDP_SOCKET_BUF_BYTES) {
            debug!("Could not raise SO_RCVBUF to 16MB (kernel limit may apply): {}", e);
        }
        if let Err(e) = sock_ref.set_send_buffer_size(UDP_SOCKET_BUF_BYTES) {
            debug!("Could not raise SO_SNDBUF to 16MB (kernel limit may apply): {}", e);
        }
    }

    pub async fn run_with_bound_socket(
        &self,
        socket: Arc<UdpSocket>,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        Self::tune_udp_socket(&socket);
        let local_addr = socket.local_addr()?;
        info!("Velocity Multipath Relay listening on UDP {}", local_addr);

        // Spawn TUN to UDP multipath downlink task
        if let Some(reader_arc) = &self.tun_reader {
            let reader_clone = Arc::clone(reader_arc);
            let sessions_clone = Arc::clone(&self.sessions);
            let ip_routes_clone = Arc::clone(&self.ip_routes);
            let socket_clone = Arc::clone(&socket);
            let running_clone = Arc::clone(&self.running);
            let quota_down = Arc::clone(&self.quota);

            tokio::task::spawn_blocking(move || {
                let mut tun_buf = [0u8; 2048];
                while running_clone.load(Ordering::Relaxed) {
                    let n = {
                        let mut dev = reader_clone.lock();
                        match dev.read_packet(&mut tun_buf) {
                            Ok(n) => n,
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
                            Err(e) if e.kind() == std::io::ErrorKind::TimedOut => continue,
                            Err(_) => {
                                std::thread::sleep(Duration::from_millis(2));
                                continue;
                            }
                        }
                    };
                    if n == 0 || quota_down.is_quota_blocked() {
                        continue;
                    }
                    let ip_packet = &tun_buf[..n];

                    // Route packet to matching session by destination IPv4 address (or single active fallback)
                    let target_session: Option<Arc<ClientSession>> = {
                        let sid_opt = if n >= 20 && (ip_packet[0] >> 4) == 4 {
                            let mut dst_ip = [0u8; 4];
                            dst_ip.copy_from_slice(&ip_packet[16..20]);
                            ip_routes_clone.read().get(&dst_ip).copied()
                        } else {
                            None
                        };

                        let sessions = sessions_clone.read();
                        sid_opt
                            .and_then(|sid| sessions.get(&sid).cloned())
                            .or_else(|| {
                                if sessions.len() == 1 {
                                    sessions.values().next().cloned()
                                } else {
                                    None
                                }
                            })
                    };

                    if let Some(session) = target_session {
                        if let Some((path_id, addr)) = session.pick_downlink_path() {
                            let seq = session.next_seq();
                            let header =
                                Vl01Header::new(flags::DATA, path_id, session.session_id, seq);
                            let mut out_pkt = [0u8; 2048];
                            let mut hdr_copy = [0u8; HEADER_SIZE];
                            if n + HEADER_SIZE + TAG_SIZE <= out_pkt.len()
                                && header.encode(&mut hdr_copy).is_ok()
                            {
                                out_pkt[..HEADER_SIZE].copy_from_slice(&hdr_copy);
                                out_pkt[HEADER_SIZE..HEADER_SIZE + n].copy_from_slice(ip_packet);
                                if let Ok(enc_len) = session.cipher.encrypt_in_place(
                                    Direction::ServerToClient,
                                    path_id,
                                    seq,
                                    &hdr_copy,
                                    &mut out_pkt[HEADER_SIZE..],
                                    n,
                                ) {
                                    let total = HEADER_SIZE + enc_len;
                                    if socket_clone.try_send_to(&out_pkt[..total], addr).is_ok() {
                                        quota_down.record_client_egress(total);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }

        // Periodic stale session eviction + quota rollover & persistence
        let sessions_gc = Arc::clone(&self.sessions);
        let ip_routes_gc = Arc::clone(&self.ip_routes);
        let quota_gc = Arc::clone(&self.quota);
        let running_gc = Arc::clone(&self.running);
        let notify_gc = Arc::clone(&self.shutdown_notify);
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(15));
            while running_gc.load(Ordering::Relaxed) {
                tokio::select! {
                    _ = notify_gc.notified() => break,
                    _ = interval.tick() => {}
                }
                if !running_gc.load(Ordering::Relaxed) {
                    break;
                }
                quota_gc.tick_rollover_and_persist();

                let mut map = sessions_gc.write();
                let mut routes = ip_routes_gc.write();
                let expired: Vec<u64> = map
                    .iter()
                    .filter(|(_, s)| s.is_expired(Duration::from_secs(120)))
                    .map(|(k, _)| *k)
                    .collect();
                for sid in expired {
                    if let Some(sess) = map.remove(&sid) {
                        if let Some(ip) = *sess.tun_ip.read() {
                            routes.remove(&ip);
                        }
                    }
                }
            }
        });

        let mut buf = [0u8; 2048];
        let mut shutdown_check = tokio::time::interval(Duration::from_millis(250));

        while self.running.load(Ordering::Relaxed) {
            let recv_res = tokio::select! {
                _ = self.shutdown_notify.notified() => break,
                _ = shutdown_check.tick() => continue,
                res = socket.recv_from(&mut buf) => res,
            };

            let (len, peer_addr) = match recv_res {
                Ok(res) => res,
                Err(e) => {
                    if self.running.load(Ordering::SeqCst) {
                        error!("UDP recv error: {:?}", e);
                    }
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

            let session = self.get_or_create_session(header.session_id);
            let mut header_slice = [0u8; HEADER_SIZE];
            header_slice.copy_from_slice(&buf[..HEADER_SIZE]);

            let plain_len = match session.cipher.decrypt_in_place(
                Direction::ClientToServer,
                header.path_id,
                header.seq_num,
                &header_slice,
                &mut buf[HEADER_SIZE..len],
                len - HEADER_SIZE,
            ) {
                Ok(l) => l,
                Err(_) => continue,
            };

            session.update_client_address(header.path_id, peer_addr);

            if header.is_speedtest() {
                if self.quota.is_quota_blocked() {
                    continue;
                }
                let reply_seq = session.next_seq();
                let mut reply_header = Vl01Header::new(
                    flags::DATA | flags::SPEEDTEST,
                    header.path_id,
                    header.session_id,
                    reply_seq,
                );
                reply_header.ack_base = header.seq_num;
                reply_header.tx_timestamp_us = header.tx_timestamp_us;

                let mut out_pkt = [0u8; 2048];
                let mut out_hdr = [0u8; HEADER_SIZE];
                if plain_len + HEADER_SIZE + TAG_SIZE <= out_pkt.len()
                    && reply_header.encode(&mut out_hdr).is_ok()
                {
                    out_pkt[..HEADER_SIZE].copy_from_slice(&out_hdr);
                    out_pkt[HEADER_SIZE..HEADER_SIZE + plain_len]
                        .copy_from_slice(&buf[HEADER_SIZE..HEADER_SIZE + plain_len]);

                    if let Ok(enc_len) = session.cipher.encrypt_in_place(
                        Direction::ServerToClient,
                        header.path_id,
                        reply_seq,
                        &out_hdr,
                        &mut out_pkt[HEADER_SIZE..],
                        plain_len,
                    ) {
                        let total_len = HEADER_SIZE + enc_len;
                        if socket.send_to(&out_pkt[..total_len], peer_addr).await.is_ok() {
                            self.quota.record_client_egress(total_len);
                        }
                    }
                }
            } else if header.is_control_probe() || header.is_keepalive() {
                let active_count = self.sessions.read().len().max(1);
                let quota_snap = self.quota.snapshot(active_count);
                let json_payload = serde_json::to_vec(&ProbeReplyPayload {
                    ok: true,
                    quota: quota_snap,
                })
                .unwrap_or_else(|_| b"PONG".to_vec());

                let reply_seq = session.next_seq();
                let mut reply_header = Vl01Header::new(
                    header.flags,
                    header.path_id,
                    header.session_id,
                    reply_seq,
                );
                reply_header.ack_base = header.seq_num;
                reply_header.tx_timestamp_us = header.tx_timestamp_us;

                let mut out_pkt = [0u8; 1024];
                let mut out_hdr = [0u8; HEADER_SIZE];
                let p_len = json_payload.len();
                if p_len + HEADER_SIZE + TAG_SIZE <= out_pkt.len()
                    && reply_header.encode(&mut out_hdr).is_ok()
                {
                    out_pkt[..HEADER_SIZE].copy_from_slice(&out_hdr);
                    out_pkt[HEADER_SIZE..HEADER_SIZE + p_len].copy_from_slice(&json_payload);

                    if let Ok(enc_len) = session.cipher.encrypt_in_place(
                        Direction::ServerToClient,
                        header.path_id,
                        reply_seq,
                        &out_hdr,
                        &mut out_pkt[HEADER_SIZE..],
                        p_len,
                    ) {
                        let total_len = HEADER_SIZE + enc_len;
                        if socket.send_to(&out_pkt[..total_len], peer_addr).await.is_ok() {
                            self.quota.record_client_egress(total_len);
                        }
                    }
                }
            } else if header.is_data() {
                if self.quota.is_quota_blocked() {
                    continue;
                }
                let payload = &buf[HEADER_SIZE..HEADER_SIZE + plain_len];
                if plain_len >= 20 && (payload[0] >> 4) == 4 {
                    let mut src_ip = [0u8; 4];
                    src_ip.copy_from_slice(&payload[12..16]);
                    if let Some(learned) = session.learn_tun_ip(src_ip) {
                        self.ip_routes.write().insert(learned, header.session_id);
                    }
                }
                debug!(
                    "Received data payload of {} bytes from session 0x{:X}",
                    plain_len, header.session_id
                );
                if let Some(writer) = &self.tun_writer {
                    if let Ok(written) = writer.write_packet(payload) {
                        self.quota.record_wan_egress(written);
                    }
                }
            }
        }

        self.quota.persist();
        Ok(())
    }

    fn get_or_create_session(&self, session_id: u64) -> Arc<ClientSession> {
        if let Some(existing) = self.sessions.read().get(&session_id) {
            return Arc::clone(existing);
        }
        let mut map = self.sessions.write();
        map.entry(session_id)
            .or_insert_with(|| {
                let cipher = Arc::new(
                    AeadCipher::from_psk(self.psk.as_bytes(), session_id)
                        .expect("Key derivation failed"),
                );
                Arc::new(ClientSession::new(session_id, cipher))
            })
            .clone()
    }
}

/// Lightweight async HTTP status/quota server (`GET /quota`, `GET /status`, `GET /health`).
pub async fn run_status_http_server(
    bind_addr: SocketAddr,
    quota: Arc<QuotaTracker>,
    sessions: Arc<RwLock<HashMap<u64, Arc<ClientSession>>>>,
    running: Arc<AtomicBool>,
    shutdown_notify: Arc<Notify>,
) {
    let listener = match TcpListener::bind(bind_addr).await {
        Ok(l) => {
            info!("Velocity HTTP status & monthly quota endpoint listening on http://{}", bind_addr);
            l
        }
        Err(e) => {
            warn!("Could not bind status HTTP listener on {}: {}", bind_addr, e);
            return;
        }
    };

    while running.load(Ordering::Relaxed) {
        let accept_res = tokio::select! {
            _ = shutdown_notify.notified() => break,
            res = listener.accept() => res,
        };

        let (mut stream, _) = match accept_res {
            Ok(conn) => conn,
            Err(_) => continue,
        };

        let quota_c = Arc::clone(&quota);
        let sessions_c = Arc::clone(&sessions);
        tokio::spawn(async move {
            let mut req_buf = [0u8; 512];
            let n = match tokio::time::timeout(Duration::from_millis(500), stream.read(&mut req_buf)).await {
                Ok(Ok(n)) if n > 0 => n,
                _ => return,
            };
            let req_line = String::from_utf8_lossy(&req_buf[..n]);
            let is_health = req_line.starts_with("GET /health");
            let active = sessions_c.read().len();
            let body = if is_health {
                serde_json::json!({ "ok": true, "activeSessions": active }).to_string()
            } else {
                serde_json::to_string(&quota_c.snapshot(active)).unwrap_or_else(|_| "{}".to_string())
            };
            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            let _ = stream.write_all(resp.as_bytes()).await;
        });
    }
}
