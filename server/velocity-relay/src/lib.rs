pub mod nat;
pub mod server;
pub mod session;
pub mod tun;

pub use nat::NatHelper;
pub use server::RelayServer;
pub use session::ClientSession;
pub use tun::{TunDevice, VirtualTun};

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;
    use std::sync::Arc;
    use velocity_proto::{flags, AeadCipher, Direction, Vl01Header, HEADER_SIZE, TAG_SIZE};

    #[tokio::test]
    async fn test_seamless_ip_nat_roaming() {
        let psk = "test_psk_roaming_key";
        let session_id = 0x1122_3344_5566_7788;
        let cipher = Arc::new(AeadCipher::from_psk(psk.as_bytes(), session_id).unwrap());
        let session = ClientSession::new(session_id, cipher);

        let initial_addr: SocketAddr = "192.168.1.100:54321".parse().unwrap();
        let changed_addr: SocketAddr = "198.51.100.42:61234".parse().unwrap();

        // 1. Initial connection from LTE IP
        let updated1 = session.update_client_address(1, initial_addr);
        assert!(updated1);
        assert_eq!(session.get_client_address(1), Some(initial_addr));

        // 2. Same address does not trigger roam update
        let updated_same = session.update_client_address(1, initial_addr);
        assert!(!updated_same);

        // 3. Seamless Roam: Cellular tower switch / NAT change
        let updated2 = session.update_client_address(1, changed_addr);
        assert!(updated2);
        assert_eq!(session.get_client_address(1), Some(changed_addr));
    }
}
