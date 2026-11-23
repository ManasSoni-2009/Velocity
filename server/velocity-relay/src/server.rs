use crate::session::ClientSession;
use crate::tun::{TunDevice, VirtualTun};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;
use tokio::net::UdpSocket;
use tracing::{debug, error, info, warn};
use velocity_proto::{
    flags, AeadCipher, Direction, PacketBufferPool, Vl01Header, HEADER_SIZE, TAG_SIZE,
};

pub struct RelayServer {
    pub bind_addr: SocketAddr,
    pub psk: String,
    pub sessions: Arc<RwLock<HashMap<u64, Arc<ClientSession>>>>,
    pub running: Arc<AtomicBool>,
    pub pool: Arc<PacketBufferPool>,
    pub tun_device: Option<Arc<parking_lot::Mutex<dyn TunDevice>>>,
}

impl RelayServer {
    pub fn new(bind_addr: SocketAddr, psk: String) -> Self {
        Self {
            bind_addr,
            psk,
            sessions: Arc::new(RwLock::new(HashMap::new())),
            running: Arc::new(AtomicBool::new(true)),
            pool: PacketBufferPool::new(128, 2048),
            tun_device: None,
        }
    }

    pub fn with_tun(mut self, tun: Arc<parking_lot::Mutex<dyn TunDevice>>) -> Self {
        self.tun_device = Some(tun);
        self
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }

    pub async fn run(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let socket = Arc::new(UdpSocket::bind(self.bind_addr).await?);
        info!("Velocity Multipath Relay listening on UDP {}", self.bind_addr);

        // Spawn TUN to UDP egress reader task
        if let Some(tun) = &self.tun_device {
            let tun_clone = Arc::clone(tun);
            let sessions_clone = Arc::clone(&self.sessions);
            let socket_clone = Arc::clone(&socket);
            let running_clone = Arc::clone(&self.running);

            tokio::task::spawn_blocking(move || {
                let mut tun_buf = [0u8; 2048];
                while running_clone.load(Ordering::Relaxed) {
                    let n = {
                        let mut dev = tun_clone.lock();
                        match dev.read_packet(&mut tun_buf) {
                            Ok(n) => n,
                            Err(_) => {
                                std::thread::sleep(std::time::Duration::from_millis(2));
                                continue;
                            }
                        }
                    };
                    if n == 0 {
                        continue;
                    }
                    let ip_packet = &tun_buf[..n];

                    // Deliver back to active client session
                    let sessions = sessions_clone.read();
                    if let Some(session) = sessions.values().next() {
                        let client_addr = {
                            let p = session.client_paths.read();
                            p.get(&0).copied().or_else(|| p.values().next().copied())
                        };
                        if let Some(addr) = client_addr {
                            let seq = session.next_seq();
                            let mut header = Vl01Header::new(flags::DATA, 0, session.session_id, seq);
                            let mut out_pkt = vec![0u8; HEADER_SIZE + n + TAG_SIZE];
                            if header.encode(&mut out_pkt[..HEADER_SIZE]).is_ok() {
                                let hdr = out_pkt[..HEADER_SIZE].to_vec();
                                out_pkt[HEADER_SIZE..HEADER_SIZE + n].copy_from_slice(ip_packet);
                                if let Ok(enc_len) = session.cipher.encrypt_in_place(
                                    Direction::ServerToClient,
                                    0,
                                    seq,
                                    &hdr,
                                    &mut out_pkt[HEADER_SIZE..],
                                    n,
                                ) {
                                    let total = HEADER_SIZE + enc_len;
                                    let _ = socket_clone.try_send_to(&out_pkt[..total], addr);
                                }
                            }
                        }
                    }
                }
            });
        }

        let mut buf = vec![0u8; 2048];

        while self.running.load(Ordering::SeqCst) {
            let (len, peer_addr) = match socket.recv_from(&mut buf).await {
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

            // Decode VL01 header
            let header = match Vl01Header::decode(&buf[..HEADER_SIZE]) {
                Ok(h) => h,
                Err(_) => continue, // Discard invalid magic/version
            };

            // Lookup or register session
            let session = self.get_or_create_session(header.session_id);

            // Decrypt payload in-place
            let header_slice = buf[..HEADER_SIZE].to_vec();
            let mut payload_buf = buf[HEADER_SIZE..len].to_vec();

            let plain_len = match session.cipher.decrypt_in_place(
                Direction::ClientToServer,
                header.path_id,
                header.seq_num,
                &header_slice,
                &mut payload_buf,
                len - HEADER_SIZE,
            ) {
                Ok(l) => l,
                Err(_) => {
                    // Auth failed / bad tag
                    continue;
                }
            };

            // Packet verified! Update client path address for seamless roaming
            session.update_client_address(header.path_id, peer_addr);

            // Handle packet flags
            if header.is_speedtest() {
                // Echo speedtest packet back to sender
                let reply_seq = session.next_seq();
                let mut reply_header = Vl01Header::new(
                    flags::DATA | flags::SPEEDTEST,
                    header.path_id,
                    header.session_id,
                    reply_seq,
                );
                reply_header.ack_base = header.seq_num;
                reply_header.tx_timestamp_us = header.tx_timestamp_us;

                let mut out_pkt = vec![0u8; HEADER_SIZE + plain_len + TAG_SIZE];
                reply_header.encode(&mut out_pkt[..HEADER_SIZE]).unwrap();
                out_pkt[HEADER_SIZE..HEADER_SIZE + plain_len].copy_from_slice(&payload_buf[..plain_len]);

                let out_header = out_pkt[..HEADER_SIZE].to_vec();
                if let Ok(enc_len) = session.cipher.encrypt_in_place(
                    Direction::ServerToClient,
                    header.path_id,
                    reply_seq,
                    &out_header,
                    &mut out_pkt[HEADER_SIZE..],
                    plain_len,
                ) {
                    let total_len = HEADER_SIZE + enc_len;
                    let _ = socket.send_to(&out_pkt[..total_len], peer_addr).await;
                }
            } else if header.is_control_probe() || header.is_keepalive() {
                // Echo probe/keepalive response
                let reply_seq = session.next_seq();
                let mut reply_header = Vl01Header::new(
                    header.flags,
                    header.path_id,
                    header.session_id,
                    reply_seq,
                );
                reply_header.ack_base = header.seq_num;
                reply_header.tx_timestamp_us = header.tx_timestamp_us;

                let mut out_pkt = vec![0u8; HEADER_SIZE + plain_len + TAG_SIZE];
                reply_header.encode(&mut out_pkt[..HEADER_SIZE]).unwrap();
                out_pkt[HEADER_SIZE..HEADER_SIZE + plain_len].copy_from_slice(&payload_buf[..plain_len]);

                let out_header = out_pkt[..HEADER_SIZE].to_vec();
                if let Ok(enc_len) = session.cipher.encrypt_in_place(
                    Direction::ServerToClient,
                    header.path_id,
                    reply_seq,
                    &out_header,
                    &mut out_pkt[HEADER_SIZE..],
                    plain_len,
                ) {
                    let total_len = HEADER_SIZE + enc_len;
                    let _ = socket.send_to(&out_pkt[..total_len], peer_addr).await;
                }
            } else if header.is_data() {
                debug!("Received data payload of {} bytes from session 0x{:X}", plain_len, header.session_id);
                if let Some(tun) = &self.tun_device {
                    let mut dev = tun.lock();
                    let _ = dev.write_packet(&payload_buf[..plain_len]);
                }
            }
        }

        Ok(())
    }

    fn get_or_create_session(&self, session_id: u64) -> Arc<ClientSession> {
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
