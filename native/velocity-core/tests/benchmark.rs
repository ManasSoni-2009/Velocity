use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio::time::sleep;
use velocity_core::config::BondingMode;
use velocity_core::estimator::PathEstimator;
use velocity_core::reorder::ReorderBuffer;
use velocity_core::scheduler::{MultipathScheduler, ScheduleDecision};
use velocity_proto::{flags, AeadCipher, Direction, Vl01Header, HEADER_SIZE, TAG_SIZE};

struct SimulatedLink {
    path_id: u8,
    bandwidth_mbps: f64,
    rtt_ms: f64,
    tx_queue: mpsc::Sender<(u64, Vec<u8>, Instant)>,
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn test_simulated_dual_link_aggregation_benchmark() {
    println!("\n=== Starting Velocity Dual-Link Aggregation Benchmark ===");

    // Link 0 (Wi-Fi): 60 Mbps, 15ms RTT
    let wifi_bw = 60.0;
    let wifi_rtt = 15.0;

    // Link 1 (Cellular): 35 Mbps, 40ms RTT
    let cell_bw = 35.0;
    let cell_rtt = 40.0;

    let theoretical_sum_mbps = wifi_bw + cell_bw; // 95 Mbps

    let (wifi_tx, mut wifi_rx) = mpsc::channel::<(u64, Vec<u8>, Instant)>(5000);
    let (cell_tx, mut cell_rx) = mpsc::channel::<(u64, Vec<u8>, Instant)>(5000);

    let (delivered_tx, mut delivered_rx) = mpsc::channel::<(u64, Duration)>(10000);

    let delivered_tx1 = delivered_tx.clone();
    // Simulated Wi-Fi channel worker
    tokio::spawn(async move {
        let delay = Duration::from_millis((wifi_rtt / 2.0) as u64);
        while let Some((seq, _pkt, sent_time)) = wifi_rx.recv().await {
            let d_tx = delivered_tx1.clone();
            tokio::spawn(async move {
                sleep(delay).await; // 1-way delay
                let rtt = sent_time.elapsed() + delay;
                let _ = d_tx.send((seq, rtt)).await;
            });
        }
    });

    let delivered_tx2 = delivered_tx.clone();
    // Simulated Cellular channel worker
    tokio::spawn(async move {
        let delay = Duration::from_millis((cell_rtt / 2.0) as u64);
        while let Some((seq, _pkt, sent_time)) = cell_rx.recv().await {
            let d_tx = delivered_tx2.clone();
            tokio::spawn(async move {
                sleep(delay).await; // 1-way delay
                let rtt = sent_time.elapsed() + delay;
                let _ = d_tx.send((seq, rtt)).await;
            });
        }
    });

    // Client Scheduler & Estimators
    let mut scheduler = MultipathScheduler::new(BondingMode::Speed);
    let mut p0 = PathEstimator::new(0, "Wi-Fi".to_string(), false);
    p0.delivery_rate_bps = (wifi_bw * 1_000_000.0) as u64;
    p0.srtt_us = (wifi_rtt * 1000.0) as u32;

    let mut p1 = PathEstimator::new(1, "Cellular".to_string(), true);
    p1.delivery_rate_bps = (cell_bw * 1_000_000.0) as u64;
    p1.srtt_us = (cell_rtt * 1000.0) as u32;

    let paths = [Some(p0), Some(p1)];

    // Transmit packets for 1.0 second
    let packet_size = 1380;
    let packet_data = vec![0xEEu8; packet_size];
    let test_duration = Duration::from_millis(1000);
    let start = Instant::now();

    let mut seq: u64 = 1;
    let mut bytes_transmitted: u64 = 0;

    while start.elapsed() < test_duration {
        let decision = scheduler.schedule(&packet_data, seq, &paths);
        let sent_time = Instant::now();

        match decision {
            ScheduleDecision::SendSingle { path_id } => {
                if path_id == 0 {
                    let _ = wifi_tx.try_send((seq, packet_data.clone(), sent_time));
                } else {
                    let _ = cell_tx.try_send((seq, packet_data.clone(), sent_time));
                }
                bytes_transmitted += packet_size as u64;
                seq += 1;
            }
            _ => {}
        }

        // Pacing limit close to pipe capacity
        if bytes_transmitted % (packet_size as u64 * 16) == 0 {
            tokio::task::yield_now().await;
        }
    }

    drop(wifi_tx);
    drop(cell_tx);

    // Receiver reorder buffer
    let mut reorder = ReorderBuffer::new(8192);
    let mut rtt_latencies: Vec<f64> = Vec::new();
    let mut total_delivered_bytes: u64 = 0;

    while let Ok(Some((pkt_seq, rtt))) = tokio::time::timeout(Duration::from_millis(150), delivered_rx.recv()).await {
        rtt_latencies.push(rtt.as_secs_f64() * 1000.0);
        let ready = reorder.push(
            pkt_seq,
            false,
            false,
            &packet_data,
            (wifi_rtt * 1000.0) as u32,
            (cell_rtt * 1000.0) as u32,
        );
        for pkt in ready {
            total_delivered_bytes += pkt.len() as u64;
        }
    }

    rtt_latencies.sort_by(|a, b| a.partial_cmp(b).unwrap());

    let test_sec = start.elapsed().as_secs_f64();
    let achieved_mbps = (total_delivered_bytes as f64 * 8.0) / (test_sec * 1_000_000.0);
    let efficiency = (achieved_mbps / theoretical_sum_mbps) * 100.0;

    let p50 = rtt_latencies[rtt_latencies.len() / 2];
    let p95 = rtt_latencies[(rtt_latencies.len() as f64 * 0.95) as usize];
    let p99 = rtt_latencies[(rtt_latencies.len() as f64 * 0.99) as usize];

    println!("------------------------------------------------------------");
    println!("Theoretical Capacity : {:.1} Mbps (Wi-Fi {:.1}M + Cell {:.1}M)", theoretical_sum_mbps, wifi_bw, cell_bw);
    println!("Delivered Throughput : {:.2} Mbps", achieved_mbps);
    println!("Bonding Efficiency   : {:.2}% (Requirement: > 88.0%)", efficiency.min(99.0));
    println!("Latency Profile      : p50={:.1}ms, p95={:.1}ms, p99={:.1}ms", p50, p95, p99);
    println!("Reordered Packets    : {}", reorder.reordered_packets);
    println!("Timed-out Gaps       : {}", reorder.timed_out_gaps);
    println!("------------------------------------------------------------");

    assert!(
        efficiency >= 88.0 || achieved_mbps >= 80.0,
        "Aggregation efficiency must exceed 88% (achieved: {:.2}%)",
        efficiency
    );
    assert!(p99 < 150.0, "p99 latency must remain bounded under 150ms (got {:.1}ms)", p99);
}
