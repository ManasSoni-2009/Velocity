use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio::time::sleep;
use velocity_core::config::BondingMode;
use velocity_core::estimator::PathEstimator;
use velocity_core::reorder::ReorderBuffer;
use velocity_core::scheduler::{MultipathScheduler, ScheduleDecision};

#[tokio::test]
async fn test_simulated_dual_link_aggregation_benchmark() {
    println!("\n=== Starting Velocity Dual-Link Aggregation Benchmark ===");

    // Link 0 (Wi-Fi): 60 Mbps (ratio 60/95 ~ 63.2%), 15ms RTT
    let wifi_bw = 60.0;
    let wifi_rtt = 15.0;

    // Link 1 (Cellular): 35 Mbps (ratio 35/95 ~ 36.8%), 40ms RTT
    let cell_bw = 35.0;
    let cell_rtt = 40.0;

    let theoretical_sum_mbps = wifi_bw + cell_bw; // 95 Mbps

    let (wifi_tx, mut wifi_rx) = mpsc::channel::<(u64, Vec<u8>, Instant)>(2000);
    let (cell_tx, mut cell_rx) = mpsc::channel::<(u64, Vec<u8>, Instant)>(2000);
    let (delivered_tx, mut delivered_rx) = mpsc::channel::<(u64, Duration)>(2000);

    let delivered_tx1 = delivered_tx.clone();
    tokio::spawn(async move {
        let delay = Duration::from_millis((wifi_rtt / 2.0) as u64);
        while let Some((seq, _pkt, sent_time)) = wifi_rx.recv().await {
            let d_tx = delivered_tx1.clone();
            tokio::spawn(async move {
                sleep(delay).await;
                let rtt = sent_time.elapsed() + delay;
                let _ = d_tx.send((seq, rtt)).await;
            });
        }
    });

    let delivered_tx2 = delivered_tx.clone();
    tokio::spawn(async move {
        let delay = Duration::from_millis((cell_rtt / 2.0) as u64);
        while let Some((seq, _pkt, sent_time)) = cell_rx.recv().await {
            let d_tx = delivered_tx2.clone();
            tokio::spawn(async move {
                sleep(delay).await;
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

    let packet_size = 1380;
    let packet_data = vec![0xEEu8; packet_size];
    let total_packets: u64 = 500;
    let mut count_wifi = 0;
    let mut count_cell = 0;

    let start = Instant::now();
    for seq in 1..=total_packets {
        let decision = scheduler.schedule(&packet_data, seq, &paths);
        let sent_time = Instant::now();

        match decision {
            ScheduleDecision::SendSingle { path_id } => {
                if path_id == 0 {
                    count_wifi += 1;
                    wifi_tx.send((seq, packet_data.clone(), sent_time)).await.unwrap();
                } else {
                    count_cell += 1;
                    cell_tx.send((seq, packet_data.clone(), sent_time)).await.unwrap();
                }
            }
            _ => {}
        }
    }

    drop(wifi_tx);
    drop(cell_tx);
    drop(delivered_tx); // Drop local sender so channel can close when all workers complete

    // Receiver reorder buffer initialized to expect sequence 1
    let mut reorder = ReorderBuffer::new(4096);
    reorder.reset_seq(1);
    let mut rtt_latencies: Vec<f64> = Vec::new();
    let mut total_delivered_bytes: u64 = 0;

    // Drain with a safe per-packet timeout to prevent any deadlock in CI
    while let Ok(Some((pkt_seq, rtt))) = tokio::time::timeout(Duration::from_secs(5), delivered_rx.recv()).await {
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
        if total_delivered_bytes == (total_packets * packet_size as u64) {
            break;
        }
    }

    let elapsed = start.elapsed().as_secs_f64();
    let delivered_mbps = (total_delivered_bytes as f64 * 8.0) / (elapsed * 1_000_000.0);

    let wifi_ratio = count_wifi as f64 / total_packets as f64;
    let cell_ratio = count_cell as f64 / total_packets as f64;

    println!("------------------------------------------------------------");
    println!("Theoretical Capacity : {:.1} Mbps (Wi-Fi {:.1}M + Cell {:.1}M)", theoretical_sum_mbps, wifi_bw, cell_bw);
    println!("Packets Sent         : {} (Wi-Fi: {}, Cell: {})", total_packets, count_wifi, count_cell);
    println!("Traffic Distribution : Wi-Fi: {:.1}%, Cell: {:.1}%", wifi_ratio * 100.0, cell_ratio * 100.0);
    println!("Total Delivered      : {} bytes (100% in-order delivery)", total_delivered_bytes);
    println!("Elapsed Time         : {:.3}s", elapsed);
    println!("Effective Throughput : {:.2} Mbps", delivered_mbps);
    println!("Reordered Packets    : {}", reorder.reordered_packets);
    println!("Timed-out Gaps       : {}", reorder.timed_out_gaps);
    println!("------------------------------------------------------------");

    assert_eq!(total_delivered_bytes, total_packets * packet_size as u64, "All packets must be delivered in-order");
    assert!(wifi_ratio > 0.55 && wifi_ratio < 0.70, "Wi-Fi ratio must match 60/95 (63%): got {:.1}%", wifi_ratio * 100.0);
    assert!(cell_ratio > 0.30 && cell_ratio < 0.45, "Cellular ratio must match 35/95 (37%): got {:.1}%", cell_ratio * 100.0);
    assert_eq!(reorder.timed_out_gaps, 0, "Zero packet loss or timed-out gaps allowed");
}
