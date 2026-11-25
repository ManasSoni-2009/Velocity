use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use velocity_core::config::TunnelConfig;
use velocity_core::speedtest::SpeedTestEngine;
use velocity_core::tunnel::{
    probe_server_udp, probe_server_udp_detailed, ConnectionState, TunnelEvent, TunnelRuntime,
};
use velocity_proto::{flags, AeadCipher, Direction, Vl01Header, HEADER_SIZE, TAG_SIZE};

async fn spawn_mock_vl01_relay(psk: String) -> (SocketAddr, Arc<AtomicBool>, tokio::task::JoinHandle<()>) {
    let sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let addr = sock.local_addr().unwrap();
    let running = Arc::new(AtomicBool::new(true));
    let running_c = Arc::clone(&running);

    let handle = tokio::spawn(async move {
        let mut buf = [0u8; 2048];
        let mut sessions: std::collections::HashMap<u64, Arc<AeadCipher>> = std::collections::HashMap::new();

        while running_c.load(Ordering::Relaxed) {
            let Ok(Ok((len, peer))) = tokio::time::timeout(Duration::from_millis(50), sock.recv_from(&mut buf)).await else {
                continue;
            };
            if len < HEADER_SIZE + TAG_SIZE {
                continue;
            }
            let Ok(hdr) = Vl01Header::decode(&buf[..HEADER_SIZE]) else {
                continue;
            };
            let cipher = sessions
                .entry(hdr.session_id)
                .or_insert_with(|| Arc::new(AeadCipher::from_psk(psk.as_bytes(), hdr.session_id).unwrap()))
                .clone();

            let mut hdr_copy = [0u8; HEADER_SIZE];
            hdr_copy.copy_from_slice(&buf[..HEADER_SIZE]);
            let Ok(plain_len) = cipher.decrypt_in_place(
                Direction::ClientToServer,
                hdr.path_id,
                hdr.seq_num,
                &hdr_copy,
                &mut buf[HEADER_SIZE..len],
                len - HEADER_SIZE,
            ) else {
                continue;
            };

            // Echo back CONTROL_PROBE, SPEEDTEST, and DATA packets (simulating round-trip TUN echo)
            let mut reply_hdr = Vl01Header::new(hdr.flags, hdr.path_id, hdr.session_id, hdr.seq_num + 100_000);
            reply_hdr.ack_base = hdr.seq_num;
            reply_hdr.tx_timestamp_us = hdr.tx_timestamp_us;

            let mut reply_payload = buf[HEADER_SIZE..HEADER_SIZE + plain_len].to_vec();
            if hdr.is_control_probe() {
                reply_payload = br#"{"ok":true,"quota":{"provider":"Oracle Cloud Free","period":"2026-11","usedBytes":1073741824,"limitBytes":10995116277760,"remainingBytes":10994042535936,"percentUsed":0.01,"activeSessions":2,"resetAtEpochMs":1796083200000,"quotaExceeded":false}}"#.to_vec();
            }

            let mut out = [0u8; 2048];
            let mut out_hdr = [0u8; HEADER_SIZE];
            reply_hdr.encode(&mut out_hdr).unwrap();
            out[..HEADER_SIZE].copy_from_slice(&out_hdr);
            out[HEADER_SIZE..HEADER_SIZE + reply_payload.len()].copy_from_slice(&reply_payload);
            if let Ok(enc_len) = cipher.encrypt_in_place(
                Direction::ServerToClient,
                hdr.path_id,
                reply_hdr.seq_num,
                &out_hdr,
                &mut out[HEADER_SIZE..],
                reply_payload.len(),
            ) {
                let _ = sock.send_to(&out[..HEADER_SIZE + enc_len], peer).await;
            }
        }
    });

    (addr, running, handle)
}

#[tokio::test]
async fn test_tunnel_honest_handshake_and_data_roundtrip() {
    let psk = "e2e_test_super_secret_psk_123456".to_string();
    let (relay_addr, relay_running, relay_task) = spawn_mock_vl01_relay(psk.clone()).await;

    let received_tun_packets: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    let rx_sink = Arc::clone(&received_tun_packets);
    let writer = Arc::new(move |pkt: &[u8]| {
        rx_sink.lock().unwrap().push(pkt.to_vec());
    });

    let (ev_tx, mut ev_rx) = mpsc::unbounded_channel();
    let cfg = TunnelConfig {
        server_host: relay_addr.ip().to_string(),
        server_port: relay_addr.port(),
        psk: psk.clone(),
        ..TunnelConfig::default()
    };

    let runtime = TunnelRuntime::new_with_options(-1, cfg, Some(ev_tx), Some(writer)).unwrap();
    runtime.start_housekeeping();

    // Must start in Connecting, NOT Bonded!
    assert_eq!(*runtime.state.state.read(), ConnectionState::Connecting);

    let sock0 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let sock1 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    assert!(runtime.register_socket(0, sock0, "wlan0".to_string(), false));
    assert!(runtime.register_socket(1, sock1, "rmnet0".to_string(), true));

    // Wait for real VL01 handshake probe echo to transition state to Bonded
    let mut saw_bonded = false;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    while tokio::time::Instant::now() < deadline {
        if let Ok(Some(ev)) = tokio::time::timeout(Duration::from_millis(200), ev_rx.recv()).await {
            if let TunnelEvent::StateChanged { state: ConnectionState::Bonded, .. } = ev {
                saw_bonded = true;
                break;
            }
        }
    }
    assert!(saw_bonded, "TunnelRuntime must transition to Bonded after real VL01 probe echo");

    // Inject an IPv4 packet and verify it travels client -> relay -> client TUN writer
    let sample_ip = b"\x45\x00\x00\x20\x00\x01\x00\x00\x40\x11\x00\x00\x0a\x42\x00\x02\x08\x08\x08\x08hello_vl01!!";
    runtime.inject_outbound_packet(sample_ip);

    tokio::time::sleep(Duration::from_millis(250)).await;
    let tun_pkts = received_tun_packets.lock().unwrap();
    assert!(!tun_pkts.is_empty(), "Expected echoed packet written back to TUN");
    assert_eq!(tun_pkts[0], sample_ip);

    let tel = runtime.get_telemetry();
    assert!(tel.wifi.active || tel.cellular.active);
    assert!(tel.effective_rtt_ms > 0.0 && tel.effective_rtt_ms < 500.0);

    runtime.stop();
    relay_running.store(false, Ordering::SeqCst);
    let _ = relay_task.await;
}

#[tokio::test]
async fn test_unreachable_relay_never_fakes_bonded_and_speedtest_errors() {
    // Bind and immediately drop a UDP socket to get a closed port
    let dead_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let dead_addr = dead_sock.local_addr().unwrap();
    drop(dead_sock);

    let cfg = TunnelConfig {
        server_host: dead_addr.ip().to_string(),
        server_port: dead_addr.port(),
        psk: "unreachable_test_psk_123456".to_string(),
        ..TunnelConfig::default()
    };

    let runtime = TunnelRuntime::new(-1, cfg.clone()).unwrap();
    runtime.start_housekeeping();
    let s0 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    runtime.register_socket(0, s0, "wlan0".to_string(), false);

    tokio::time::sleep(Duration::from_millis(600)).await;
    assert_ne!(
        *runtime.state.state.read(),
        ConnectionState::Bonded,
        "Unreachable relay must NEVER report Bonded"
    );
    runtime.stop();

    // SpeedTest must return explicit Err instead of fake numbers
    let speed_engine = SpeedTestEngine::new(cfg.clone());
    let speed_res = speed_engine.run_test(0, 0).await;
    assert!(speed_res.is_err(), "SpeedTest against dead relay must fail honestly");

    // Probe must fail with timeout
    let probe_res = probe_server_udp(&cfg.server_host, cfg.server_port, &cfg.psk, 0, None, 250).await;
    assert!(probe_res.is_err());
}

#[tokio::test]
async fn test_probe_and_speedtest_succeed_against_live_relay() {
    let psk = "live_probe_speed_secret_12345".to_string();
    let (relay_addr, relay_running, relay_task) = spawn_mock_vl01_relay(psk.clone()).await;

    let outcome = probe_server_udp_detailed(
        &relay_addr.ip().to_string(),
        relay_addr.port(),
        &psk,
        0,
        None,
        1000,
    )
    .await
    .expect("Probe against live relay must succeed");
    assert!(outcome.rtt_ms >= 0.0 && outcome.rtt_ms < 500.0);
    let q = outcome.quota.expect("Expected monthly quota payload from relay probe");
    assert_eq!(q.provider, "Oracle Cloud Free");
    assert_eq!(q.used_bytes, 1_073_741_824);
    assert_eq!(q.active_sessions, 2);

    // Wrong PSK must fail authentication
    let bad_psk_res = probe_server_udp(
        &relay_addr.ip().to_string(),
        relay_addr.port(),
        "wrong_psk_completely_different",
        0,
        None,
        300,
    )
    .await;
    assert!(bad_psk_res.is_err());

    let cfg = TunnelConfig {
        server_host: relay_addr.ip().to_string(),
        server_port: relay_addr.port(),
        psk,
        ..TunnelConfig::default()
    };
    let engine = SpeedTestEngine::new(cfg);
    let res = engine.run_test(0, 0).await.expect("SpeedTest against live relay must succeed");
    assert!(res.combined_download_mbps > 0.0);
    assert!(res.wifi_download_mbps > 0.0);
    assert!(res.cellular_download_mbps > 0.0);

    relay_running.store(false, Ordering::SeqCst);
    let _ = relay_task.await;
}
