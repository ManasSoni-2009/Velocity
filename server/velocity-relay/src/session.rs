use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;
use tracing::info;
use velocity_proto::AeadCipher;

pub struct ClientSession {
    pub session_id: u64,
    pub cipher: Arc<AeadCipher>,
    pub client_paths: RwLock<HashMap<u8, SocketAddr>>,
    pub last_seen: RwLock<Instant>,
    pub seq_out: AtomicU64,
    pub next_expected_seq: AtomicU64,
}

impl ClientSession {
    pub fn new(session_id: u64, cipher: Arc<AeadCipher>) -> Self {
        Self {
            session_id,
            cipher,
            client_paths: RwLock::new(HashMap::new()),
            last_seen: RwLock::new(Instant::now()),
            seq_out: AtomicU64::new(1),
            next_expected_seq: AtomicU64::new(1),
        }
    }

    /// Update client remote address for a specific path ID (Seamless IP/NAT Roaming)
    pub fn update_client_address(&self, path_id: u8, new_addr: SocketAddr) -> bool {
        let mut paths = self.client_paths.write();
        let mut last_seen = self.last_seen.write();
        *last_seen = Instant::now();

        match paths.get(&path_id) {
            Some(&old_addr) if old_addr == new_addr => false,
            Some(&old_addr) => {
                info!(
                    "Session 0x{:X} path {} seamlessly roamed from {} to {}",
                    self.session_id, path_id, old_addr, new_addr
                );
                paths.insert(path_id, new_addr);
                true
            }
            None => {
                info!(
                    "Session 0x{:X} registered path {} at {}",
                    self.session_id, path_id, new_addr
                );
                paths.insert(path_id, new_addr);
                true
            }
        }
    }

    pub fn get_client_address(&self, path_id: u8) -> Option<SocketAddr> {
        self.client_paths.read().get(&path_id).copied()
    }

    pub fn next_seq(&self) -> u64 {
        self.seq_out.fetch_add(1, Ordering::SeqCst)
    }
}
