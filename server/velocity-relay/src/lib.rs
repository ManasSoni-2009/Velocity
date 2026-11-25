pub mod nat;
pub mod quota;
pub mod server;
pub mod session;
pub mod tun;

pub use nat::NatHelper;
pub use quota::{QuotaSnapshot, QuotaTracker, DEFAULT_ORACLE_LIMIT_GB};
pub use server::{run_status_http_server, RelayServer};
pub use session::ClientSession;
pub use tun::{TunDevice, TunReader, TunWriter, VirtualTun};

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;
    use std::sync::Arc;
    use tokio::net::UdpSocket;
    use velocity_proto::{flags, AeadCipher, Direction, Vl01Header, HEADER_SIZE, TAG_SIZE};

    #[tokio::test]
    async fn test_seamless_ip_nat_roaming() {
        let psk = "test_psk_roaming_key";
        let session_id = 0x1122_3344_5566_7788;
        let cipher = Arc::new(AeadCipher::from_psk(psk.as_bytes(), session_id).unwrap());
        let session = ClientSession::new(session_id, cipher);

        let initial_addr: SocketAddr = "192.168.1.100:54321".parse().unwrap();
        let changed_addr: SocketAddr = "198.51.100.42:61234".parse().unwrap();

        let updated1 = session.update_client_address(1, initial_addr);
        assert!(updated1);
        assert_eq!(session.get_client_address(1), Some(initial_addr));

        let updated_same = session.update_client_address(1, initial_addr);
        assert!(!updated_same);

        let updated2 = session.update_client_address(1, changed_addr);
        assert!(updated2);
        assert_eq!(session.get_client_address(1), Some(changed_addr));
    }

    #[tokio::test]
    async fn test_relay_probe_and_multipath_downlink_routing() {
        let psk = "relay_integration_secret_12345".to_string();
        let (server_tun, mut internet_tun) = VirtualTun::new_pair();
        let (reader, writer) = server_tun.split();

        let udp = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let server_addr = udp.local_addr().unwrap();

        let relay = Arc::new(
            RelayServer::new(server_addr, psk.clone())
                .with_split_tun(Box::new(reader), Arc::new(writer)),
        );
        let relay_run = Arc::clone(&relay);
        let sock_run = Arc::clone(&udp);
        let srv_task = tokio::spawn(async move {
            let _ = relay_run.run_with_bound_socket(sock_run).await;
        });

        let session_id = 0xDEAD_BEEF_0001_0002u64;
        let cipher = AeadCipher::from_psk(psk.as_bytes(), session_id).unwrap();

        // Two separate client path sockets (Wi-Fi path 0, Cellular path 1)
        let sock0 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sock1 = UdpSocket::bind("127.0.0.1:0").await.unwrap();

        // Register both paths via encrypted CONTROL_PROBE & verify quota JSON in response
        for (pid, sock) in [(0u8, &sock0), (1u8, &sock1)] {
            let mut hdr = Vl01Header::new(flags::CONTROL_PROBE, pid, session_id, (pid as u64) + 1);
            hdr.tx_timestamp_us = 12345;
            let mut pkt = [0u8; HEADER_SIZE + 4 + TAG_SIZE];
            let mut hdr_bytes = [0u8; HEADER_SIZE];
            hdr.encode(&mut hdr_bytes).unwrap();
            pkt[..HEADER_SIZE].copy_from_slice(&hdr_bytes);
            pkt[HEADER_SIZE..HEADER_SIZE + 4].copy_from_slice(b"PING");
            let enc_len = cipher
                .encrypt_in_place(Direction::ClientToServer, pid, (pid as u64) + 1, &hdr_bytes, &mut pkt[HEADER_SIZE..], 4)
                .unwrap();
            sock.send_to(&pkt[..HEADER_SIZE + enc_len], server_addr).await.unwrap();

            let mut rx = [0u8; 1024];
            let (rx_len, _) = tokio::time::timeout(std::time::Duration::from_secs(2), sock.recv_from(&mut rx))
                .await
                .unwrap()
                .unwrap();
            let rhdr = Vl01Header::decode(&rx[..HEADER_SIZE]).unwrap();
            assert_eq!(rhdr.tx_timestamp_us, 12345);
            let mut rhdr_bytes = [0u8; HEADER_SIZE];
            rhdr_bytes.copy_from_slice(&rx[..HEADER_SIZE]);
            let plain_len = cipher
                .decrypt_in_place(Direction::ServerToClient, rhdr.path_id, rhdr.seq_num, &rhdr_bytes, &mut rx[HEADER_SIZE..rx_len], rx_len - HEADER_SIZE)
                .unwrap();
            let reply_json: serde_json::Value = serde_json::from_slice(&rx[HEADER_SIZE..HEADER_SIZE + plain_len]).unwrap();
            assert_eq!(reply_json["ok"], true);
            assert!(reply_json["quota"]["limitBytes"].as_u64().unwrap() > 0);
        }

        // Send an uplink IPv4 packet from client TUN IP 10.66.0.2 -> server learns IP route
        let mut fake_ip = vec![0u8; 28];
        fake_ip[0] = 0x45;
        fake_ip[12..16].copy_from_slice(&[10, 66, 0, 2]);
        fake_ip[16..20].copy_from_slice(&[8, 8, 8, 8]);

        let dhdr = Vl01Header::new(flags::DATA, 0, session_id, 10);
        let mut dpkt = vec![0u8; HEADER_SIZE + fake_ip.len() + TAG_SIZE];
        let mut dhdr_bytes = [0u8; HEADER_SIZE];
        dhdr.encode(&mut dhdr_bytes).unwrap();
        dpkt[..HEADER_SIZE].copy_from_slice(&dhdr_bytes);
        dpkt[HEADER_SIZE..HEADER_SIZE + fake_ip.len()].copy_from_slice(&fake_ip);
        let enc_len = cipher
            .encrypt_in_place(Direction::ClientToServer, 0, 10, &dhdr_bytes, &mut dpkt[HEADER_SIZE..], fake_ip.len())
            .unwrap();
        sock0.send_to(&dpkt[..HEADER_SIZE + enc_len], server_addr).await.unwrap();

        let (got_n, mut internet_tun) = tokio::task::spawn_blocking(move || {
            let mut tun_rx_buf = [0u8; 256];
            let n = loop {
                match internet_tun.read_packet(&mut tun_rx_buf) {
                    Ok(n) => break n,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
                    Err(e) => panic!("VirtualTun read failed: {:?}", e),
                }
            };
            (n, internet_tun)
        })
        .await
        .unwrap();
        assert_eq!(got_n, fake_ip.len());

        // Now send a downlink IPv4 packet from internet 8.8.8.8 -> client 10.66.0.2
        let mut down_ip = vec![0u8; 24];
        down_ip[0] = 0x45;
        down_ip[12..16].copy_from_slice(&[8, 8, 8, 8]);
        down_ip[16..20].copy_from_slice(&[10, 66, 0, 2]);
        internet_tun.write_packet(&down_ip).unwrap();

        let mut d_buf0 = [0u8; 256];
        let mut d_buf1 = [0u8; 256];
        let down_res = tokio::time::timeout(std::time::Duration::from_secs(2), async {
            tokio::select! {
                r0 = sock0.recv_from(&mut d_buf0) => (0u8, r0.unwrap().0, d_buf0),
                r1 = sock1.recv_from(&mut d_buf1) => (1u8, r1.unwrap().0, d_buf1),
            }
        })
        .await
        .expect("Did not receive downlink encrypted packet from relay");
        let (rx_path, rx_n, mut down_pkt) = down_res;
        assert!(rx_n >= HEADER_SIZE + TAG_SIZE + down_ip.len());
        let dh = Vl01Header::decode(&down_pkt[..HEADER_SIZE]).unwrap();
        assert_eq!(dh.path_id, rx_path);
        let mut dh_bytes = [0u8; HEADER_SIZE];
        dh_bytes.copy_from_slice(&down_pkt[..HEADER_SIZE]);
        let plain_down = cipher
            .decrypt_in_place(Direction::ServerToClient, dh.path_id, dh.seq_num, &dh_bytes, &mut down_pkt[HEADER_SIZE..rx_n], rx_n - HEADER_SIZE)
            .unwrap();
        assert_eq!(plain_down, down_ip.len());

        relay.stop();
        let _ = srv_task.await;
    }
}
