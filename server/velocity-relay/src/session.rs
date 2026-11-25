use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tracing::info;
use velocity_proto::AeadCipher;

#[derive(Debug, Clone)]
pub struct PathInfo {
    pub addr: SocketAddr,
    pub last_seen: Instant,
}

pub struct ClientSession {
    pub session_id: u64,
    pub cipher: Arc<AeadCipher>,
    pub client_paths: RwLock<HashMap<u8, PathInfo>>,
    pub tun_ip: RwLock<Option<[u8; 4]>>,
    pub last_seen: RwLock<Instant>,
    pub seq_out: AtomicU64,
    pub next_expected_seq: AtomicU64,
    downlink_rr: AtomicUsize,
}

impl ClientSession {
    pub fn new(session_id: u64, cipher: Arc<AeadCipher>) -> Self {
        Self {
            session_id,
            cipher,
            client_paths: RwLock::new(HashMap::new()),
            tun_ip: RwLock::new(None),
            last_seen: RwLock::new(Instant::now()),
            seq_out: AtomicU64::new(1),
            next_expected_seq: AtomicU64::new(1),
            downlink_rr: AtomicUsize::new(0),
        }
    }

    /// Update client remote address for a specific path ID (Seamless IP/NAT Roaming).
    pub fn update_client_address(&self, path_id: u8, new_addr: SocketAddr) -> bool {
        let now = Instant::now();
        let mut paths = self.client_paths.write();
        *self.last_seen.write() = now;

        match paths.get_mut(&path_id) {
            Some(info) if info.addr == new_addr => {
                info.last_seen = now;
                false
            }
            Some(info) => {
                info!(
                    "Session 0x{:X} path {} seamlessly roamed from {} to {}",
                    self.session_id, path_id, info.addr, new_addr
                );
                info.addr = new_addr;
                info.last_seen = now;
                true
            }
            None => {
                info!(
                    "Session 0x{:X} registered path {} at {}",
                    self.session_id, path_id, new_addr
                );
                paths.insert(path_id, PathInfo { addr: new_addr, last_seen: now });
                true
            }
        }
    }

    pub fn learn_tun_ip(&self, ip: [u8; 4]) -> Option<[u8; 4]> {
        let mut guard = self.tun_ip.write();
        let prev = *guard;
        if prev != Some(ip) {
            *guard = Some(ip);
            Some(ip)
        } else {
            None
        }
    }

    pub fn get_client_address(&self, path_id: u8) -> Option<SocketAddr> {
        self.client_paths.read().get(&path_id).map(|p| p.addr)
    }

    /// Pick an active downlink path (round-robin across alive paths seen within 5 seconds).
    pub fn pick_downlink_path(&self) -> Option<(u8, SocketAddr)> {
        let paths = self.client_paths.read();
        if paths.is_empty() {
            return None;
        }
        let fresh_cutoff = Duration::from_secs(5);
        let mut active: Vec<(u8, SocketAddr)> = paths
            .iter()
            .filter(|(_, info)| info.last_seen.elapsed() <= fresh_cutoff)
            .map(|(pid, info)| (*pid, info.addr))
            .collect();

        if active.is_empty() {
            active = paths.iter().map(|(pid, info)| (*pid, info.addr)).collect();
        }
        active.sort_by_key(|(pid, _)| *pid);
        let idx = self.downlink_rr.fetch_add(1, Ordering::Relaxed) % active.len();
        Some(active[idx])
    }

    pub fn next_seq(&self) -> u64 {
        self.seq_out.fetch_add(1, Ordering::SeqCst)
    }

    pub fn is_expired(&self, ttl: Duration) -> bool {
        self.last_seen.read().elapsed() > ttl
    }
}
