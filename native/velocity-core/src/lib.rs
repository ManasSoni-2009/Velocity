pub mod config;
pub mod estimator;
pub mod reorder;
pub mod scheduler;
pub mod speedtest;
pub mod telemetry;
pub mod tunnel;

pub use config::{BondingMode, TunnelConfig};
pub use estimator::PathEstimator;
pub use reorder::ReorderBuffer;
pub use scheduler::{MultipathScheduler, ScheduleDecision};
pub use speedtest::{SpeedTestEngine, SpeedTestResult};
pub use telemetry::{CombinedTelemetry, LinkTelemetry};
pub use tunnel::{probe_server_udp, ConnectionState, TunnelEvent, TunnelRuntime, TunnelState};

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn test_config_deserializes_both_psk_and_psk_hex() {
        let legacy_json = r#"{"server_host":"127.0.0.1","server_port":51820,"psk_hex":"00112233445566778899aabbccddeeff","mode":0,"mtu":1380,"fec_enabled":true}"#;
        let cfg1: TunnelConfig = serde_json::from_str(legacy_json).expect("psk_hex must parse");
        assert_eq!(cfg1.psk, "00112233445566778899aabbccddeeff");
        assert!(cfg1.fec_enabled);

        let canonical_json = r#"{"server_host":"127.0.0.1","server_port":51820,"psk":"my-secret-psk","mode":1,"wifi_bias_percent":85}"#;
        let cfg2: TunnelConfig = serde_json::from_str(canonical_json).expect("psk must parse");
        assert_eq!(cfg2.psk, "my-secret-psk");
        assert_eq!(cfg2.mode, 1);
        assert_eq!(cfg2.wifi_bias_percent, 85);
    }

    #[test]
    fn test_telemetry_and_speedtest_json_camel_case_contract() {
        let tel = CombinedTelemetry::default();
        let json = serde_json::to_string(&tel).unwrap();
        assert!(json.contains("\"combinedDownloadMbps\""));
        assert!(json.contains("\"effectiveRttMs\""));
        assert!(json.contains("\"hasInternet\""));
        assert!(json.contains("\"type\":\"WIFI\""));

        let res = SpeedTestResult {
            wifi_download_mbps: 45.2,
            wifi_upload_mbps: 12.1,
            cellular_download_mbps: 30.0,
            cellular_upload_mbps: 8.5,
            combined_download_mbps: 72.3,
            combined_upload_mbps: 19.8,
            wifi_rtt_ms: 14.0,
            cellular_rtt_ms: 36.0,
            combined_rtt_ms: 18.0,
            gain_percent: 60,
            duration_ms: 2500,
        };
        let rjson = serde_json::to_string(&res).unwrap();
        assert!(rjson.contains("\"wifiDownloadMbps\""));
        assert!(rjson.contains("\"combinedDownloadMbps\""));
        assert!(rjson.contains("\"gainPercent\""));
    }

    #[test]
    fn test_estimator_ewma_and_bdp() {
        let mut est = PathEstimator::new(0, "Wi-Fi".to_string(), false);

        est.record_rtt_sample(20_000);
        est.record_rtt_sample(30_000);
        est.record_rtt_sample(25_000);

        assert!(est.srtt_us >= 20_000 && est.srtt_us <= 30_000);
        assert_eq!(est.min_rtt_us, 20_000);
        assert!(est.jitter_us > 0);
        assert!(est.has_verified_path());

        est.record_ack(1500 * 10, 10_000);
        assert!(est.delivery_rate_bps > 1_000_000);
        assert!(est.bdp_bytes() >= 16_384);
    }

    #[test]
    fn test_scheduler_speed_mode_bdp_deficit() {
        let mut scheduler = MultipathScheduler::new(BondingMode::Speed);

        let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
        p0.delivery_rate_bps = 50_000_000;
        p0.srtt_us = 15_000;

        let mut p1 = PathEstimator::new(1, "Cellular".to_string(), true);
        p1.delivery_rate_bps = 25_000_000;
        p1.srtt_us = 35_000;

        let paths = [Some(p0.clone()), Some(p1.clone())];
        let dummy_pkt = vec![0u8; 1380];

        let mut count0 = 0;
        let mut count1 = 0;

        for seq in 1..=30 {
            if let ScheduleDecision::SendSingle { path_id } = scheduler.schedule(&dummy_pkt, seq, &paths) {
                if path_id == 0 {
                    count0 += 1;
                } else {
                    count1 += 1;
                }
            }
        }

        assert!(count0 > count1, "Path 0 should get more packets: {} vs {}", count0, count1);
        assert!(count1 > 0, "Path 1 should also receive deficit allocation: {}", count1);
    }

    #[test]
    fn test_scheduler_low_latency_mode() {
        let mut scheduler = MultipathScheduler::new(BondingMode::LowLatency);

        let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
        p0.srtt_us = 10_000;

        let mut p1 = PathEstimator::new(1, "Cellular".to_string(), true);
        p1.srtt_us = 40_000;

        let paths = [Some(p0.clone()), Some(p1.clone())];
        let small_interactive_pkt = vec![0u8; 120];

        let decision = scheduler.schedule(&small_interactive_pkt, 1, &paths);
        match decision {
            ScheduleDecision::SendSingle { path_id } => assert_eq!(path_id, 0),
            _ => panic!("Expected single path to min RTT"),
        }

        p0.loss_rate = 0.05;
        let paths_loss = [Some(p0), Some(p1)];

        let decision2 = scheduler.schedule(&small_interactive_pkt, 2, &paths_loss);
        match decision2 {
            ScheduleDecision::SendDuplicate { primary_path, secondary_path } => {
                assert_eq!(primary_path, 0);
                assert_eq!(secondary_path, 1);
            }
            _ => panic!("Expected duplicate across both paths on loss spike"),
        }
    }

    #[test]
    fn test_scheduler_failover_mode() {
        let mut scheduler = MultipathScheduler::new(BondingMode::Failover);

        let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
        let p1 = PathEstimator::new(1, "Cellular".to_string(), true);

        let dummy_pkt = vec![0u8; 500];

        let d1 = scheduler.schedule(&dummy_pkt, 1, &[Some(p0.clone()), Some(p1.clone())]);
        match d1 {
            ScheduleDecision::SendSingle { path_id } => assert_eq!(path_id, 0),
            _ => panic!("Expected Path 0"),
        }

        p0.is_active = false;
        p0.consecutive_timeouts = 10;
        p0.last_seen = std::time::Instant::now() - Duration::from_millis(500);

        std::thread::sleep(Duration::from_millis(85));
        let d2 = scheduler.schedule(&dummy_pkt, 2, &[Some(p0), Some(p1)]);
        match d2 {
            ScheduleDecision::SendSingle { path_id } => assert_eq!(path_id, 1),
            _ => panic!("Expected failover to Path 1"),
        }
    }

    #[test]
    fn test_reorder_buffer_out_of_order_and_adaptive_timeout() {
        let mut reorder = ReorderBuffer::new(128);
        let rtt0 = 20_000;
        let rtt1 = 50_000;

        let out1 = reorder.push(1, false, false, b"Pkt1", rtt0, rtt1);
        assert_eq!(out1.len(), 1);
        assert_eq!(out1[0], b"Pkt1");

        let out3 = reorder.push(3, false, false, b"Pkt3", rtt0, rtt1);
        assert_eq!(out3.len(), 0);

        let out2 = reorder.push(2, false, false, b"Pkt2", rtt0, rtt1);
        assert_eq!(out2.len(), 2);
        assert_eq!(out2[0], b"Pkt2");
        assert_eq!(out2[1], b"Pkt3");
    }

    #[test]
    fn test_fec_parity_recovery() {
        let mut reorder = ReorderBuffer::new(128);
        let rtt = 15_000;

        let d0 = b"PacketDataA";
        let d1 = b"PacketDataB";
        let d2 = b"PacketDataC";
        let d3 = b"PacketDataD";

        let mut parity = vec![0u8; d0.len()];
        for (i, b) in d0.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d1.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d2.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d3.iter().enumerate() { parity[i] ^= b; }

        let o0 = reorder.push(0, false, false, d0, rtt, rtt);
        assert_eq!(o0.len(), 1);

        let _ = reorder.push(2, false, false, d2, rtt, rtt);
        let _ = reorder.push(3, false, false, d3, rtt, rtt);

        let recovered = reorder.push(0, true, false, &parity, rtt, rtt);

        assert_eq!(reorder.fec_recovered_packets, 1);
        assert_eq!(recovered.len(), 3);
        assert_eq!(recovered[0], d1);
        assert_eq!(recovered[1], d2);
        assert_eq!(recovered[2], d3);
    }
}
