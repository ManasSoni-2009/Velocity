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
pub use tunnel::{ConnectionState, TunnelRuntime, TunnelState};

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn test_estimator_ewma_and_bdp() {
        let mut est = PathEstimator::new(0, "Wi-Fi".to_string(), false);

        // Feed RTT samples: 20ms, 30ms, 25ms
        est.record_rtt_sample(20_000);
        est.record_rtt_sample(30_000);
        est.record_rtt_sample(25_000);

        assert!(est.srtt_us >= 20_000 && est.srtt_us <= 30_000);
        assert_eq!(est.min_rtt_us, 20_000);
        assert!(est.jitter_us > 0);

        // Feed ACK delivery rate
        est.record_ack(1500 * 10, 10_000); // 15KB in 10ms = ~12 Mbps
        assert!(est.delivery_rate_bps > 1_000_000);
        assert!(est.bdp_bytes() >= 16_384);
    }

    #[test]
    fn test_scheduler_speed_mode_bdp_deficit() {
        let mut scheduler = MultipathScheduler::new(BondingMode::Speed);

        let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
        p0.delivery_rate_bps = 50_000_000; // 50 Mbps
        p0.srtt_us = 15_000;              // 15ms

        let mut p1 = PathEstimator::new(1, "Cellular".to_string(), true);
        p1.delivery_rate_bps = 25_000_000; // 25 Mbps
        p1.srtt_us = 35_000;              // 35ms

        let paths = [Some(p0.clone()), Some(p1.clone())];
        let dummy_pkt = vec![0u8; 1380];

        let mut count0 = 0;
        let mut count1 = 0;

        for seq in 1..=30 {
            match scheduler.schedule(&dummy_pkt, seq, &paths) {
                ScheduleDecision::SendSingle { path_id } => {
                    if path_id == 0 {
                        count0 += 1;
                    } else {
                        count1 += 1;
                    }
                }
                _ => {}
            }
        }

        // Wi-Fi has 2x higher delivery rate and lower RTT, so it should receive majority of traffic
        assert!(count0 > count1, "Path 0 should get more packets: {} vs {}", count0, count1);
        assert!(count1 > 0, "Path 1 should also receive deficit allocation: {}", count1);
    }

    #[test]
    fn test_scheduler_low_latency_mode() {
        let mut scheduler = MultipathScheduler::new(BondingMode::LowLatency);

        let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
        p0.srtt_us = 10_000; // 10ms (lower RTT)

        let mut p1 = PathEstimator::new(1, "Cellular".to_string(), true);
        p1.srtt_us = 40_000; // 40ms

        let paths = [Some(p0.clone()), Some(p1.clone())];
        let small_interactive_pkt = vec![0u8; 120];

        // Normal condition: should route to lowest RTT path (p0)
        let decision = scheduler.schedule(&small_interactive_pkt, 1, &paths);
        match decision {
            ScheduleDecision::SendSingle { path_id } => assert_eq!(path_id, 0),
            _ => panic!("Expected single path to min RTT"),
        }

        // Introduce loss spike on best path
        p0.loss_rate = 0.05;
        let paths_loss = [Some(p0), Some(p1)];

        // With loss > 1%, small packets must be duplicated across both paths
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

        // While p0 is alive, 100% on p0
        let d1 = scheduler.schedule(&dummy_pkt, 1, &[Some(p0.clone()), Some(p1.clone())]);
        match d1 {
            ScheduleDecision::SendSingle { path_id } => assert_eq!(path_id, 0),
            _ => panic!("Expected Path 0"),
        }

        // Kill p0
        p0.is_active = false;
        p0.consecutive_timeouts = 10;
        // Fast-forward last_seen
        p0.last_seen = std::time::Instant::now() - Duration::from_millis(500);

        // After failure timeout (>80ms), must failover to p1 (<100ms requirement)
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
        let rtt0 = 20_000; // 20ms
        let rtt1 = 50_000; // 50ms -> |20 - 50| = 30ms -> 1.5 * 30 + 10 = 55ms timeout

        // Packet 1 arrives first
        let out1 = reorder.push(1, false, false, b"Pkt1", rtt0, rtt1);
        assert_eq!(out1.len(), 1);
        assert_eq!(out1[0], b"Pkt1");

        // Packet 3 arrives (Packet 2 missing / gap!)
        let out3 = reorder.push(3, false, false, b"Pkt3", rtt0, rtt1);
        assert_eq!(out3.len(), 0); // Must be held in buffer

        // Packet 2 arrives now
        let out2 = reorder.push(2, false, false, b"Pkt2", rtt0, rtt1);
        // Both Packet 2 and Packet 3 should now be delivered in-order!
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

        // Compute XOR parity across 4 packets
        let mut parity = vec![0u8; d0.len()];
        for (i, b) in d0.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d1.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d2.iter().enumerate() { parity[i] ^= b; }
        for (i, b) in d3.iter().enumerate() { parity[i] ^= b; }

        // Feed d0 (seq 0)
        let o0 = reorder.push(0, false, false, d0, rtt, rtt);
        assert_eq!(o0.len(), 1);

        // Drop d1 (seq 1 missing!)
        // Feed d2 (seq 2) and d3 (seq 3)
        let _ = reorder.push(2, false, false, d2, rtt, rtt);
        let _ = reorder.push(3, false, false, d3, rtt, rtt);

        // Feed FEC parity packet (block base 0)
        let recovered = reorder.push(0, true, false, &parity, rtt, rtt);

        // Reorder buffer should have reconstructed d1 and drained d1, d2, d3!
        assert_eq!(reorder.fec_recovered_packets, 1);
        assert_eq!(recovered.len(), 3);
        assert_eq!(recovered[0], d1);
        assert_eq!(recovered[1], d2);
        assert_eq!(recovered[2], d3);
    }
}
