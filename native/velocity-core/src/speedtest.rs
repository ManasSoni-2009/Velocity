use crate::config::TunnelConfig;
use crate::tunnel::resolve_server_addr;
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use velocity_proto::{flags, AeadCipher, Direction, Vl01Header, HEADER_SIZE, TAG_SIZE};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpeedTestResult {
    pub wifi_download_mbps: f64,
    pub wifi_upload_mbps: f64,
    pub cellular_download_mbps: f64,
    pub cellular_upload_mbps: f64,
    pub combined_download_mbps: f64,
    pub combined_upload_mbps: f64,
    pub wifi_rtt_ms: f64,
    pub cellular_rtt_ms: f64,
    pub combined_rtt_ms: f64,
    pub gain_percent: i32,
    pub duration_ms: u64,
}

pub struct SpeedTestEngine {
    config: TunnelConfig,
    running: Arc<AtomicBool>,
}

impl SpeedTestEngine {
    pub fn new(config: TunnelConfig) -> Self {
        Self {
            config,
            running: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
    }

    /// Run bidirectional 3-phase multipath speedtest binding new ephemeral ports.
    pub async fn run_test(
        &self,
        wifi_local_port: u16,
        cell_local_port: u16,
    ) -> Result<SpeedTestResult, String> {
        let wifi_socket = UdpSocket::bind(format!("0.0.0.0:{}", wifi_local_port))
            .await
            .map_err(|e| format!("Failed to bind Wi-Fi test socket: {}", e))?;
        let cell_socket = UdpSocket::bind(format!("0.0.0.0:{}", cell_local_port))
            .await
            .map_err(|e| format!("Failed to bind Cellular test socket: {}", e))?;

        self.run_with_sockets(Some(Arc::new(wifi_socket)), Some(Arc::new(cell_socket)))
            .await
    }

    /// Run bidirectional multipath speedtest over already-bound sockets (e.g., bound to physical Android Networks).
    pub async fn run_with_sockets(
        &self,
        wifi_socket: Option<Arc<UdpSocket>>,
        cell_socket: Option<Arc<UdpSocket>>,
    ) -> Result<SpeedTestResult, String> {
        if wifi_socket.is_none() && cell_socket.is_none() {
            return Err("No internet paths available to benchmark".to_string());
        }
        if self.config.psk.trim().is_empty() {
            return Err("Relay pre-shared key cannot be empty".to_string());
        }

        self.running.store(true, Ordering::SeqCst);
        let overall_start = Instant::now();
        let server_addr = resolve_server_addr(&self.config.server_host, self.config.server_port)?;

        let session_id = self.config.session_id.unwrap_or(0xCAFE_BABE_1234_5678);
        let cipher = Arc::new(
            AeadCipher::from_psk(self.config.psk.as_bytes(), session_id)
                .map_err(|e| format!("Crypto init error: {:?}", e))?,
        );

        let (wifi_dl, wifi_ul, wifi_rtt, wifi_ok) = if let Some(ref ws) = wifi_socket {
            self.run_phase(
                &[(0u8, Arc::clone(ws))],
                server_addr,
                session_id,
                Arc::clone(&cipher),
                750,
            )
            .await
        } else {
            (0.0, 0.0, 0.0, false)
        };

        let (cell_dl, cell_ul, cell_rtt, cell_ok) = if let Some(ref cs) = cell_socket {
            self.run_phase(
                &[(1u8, Arc::clone(cs))],
                server_addr,
                session_id,
                Arc::clone(&cipher),
                750,
            )
            .await
        } else {
            (0.0, 0.0, 0.0, false)
        };

        let mut both = Vec::new();
        if wifi_ok {
            if let Some(ref ws) = wifi_socket {
                both.push((0u8, Arc::clone(ws)));
            }
        }
        if cell_ok {
            if let Some(ref cs) = cell_socket {
                both.push((1u8, Arc::clone(cs)));
            }
        }

        if both.is_empty() {
            self.running.store(false, Ordering::SeqCst);
            return Err(format!(
                "Relay {} did not respond — check server status, key, and internet connection",
                server_addr
            ));
        }

        let (combined_dl, combined_ul, combined_rtt, combined_ok) = self
            .run_phase(&both, server_addr, session_id, Arc::clone(&cipher), 1100)
            .await;

        self.running.store(false, Ordering::SeqCst);

        if !combined_ok {
            return Err("Relay stopped responding during combined benchmark".to_string());
        }

        let best_single = wifi_dl.max(cell_dl);
        let gain_percent = if best_single > 0.05 && wifi_ok && cell_ok {
            (((combined_dl - best_single) / best_single) * 100.0).round() as i32
        } else {
            0
        };

        Ok(SpeedTestResult {
            wifi_download_mbps: wifi_dl,
            wifi_upload_mbps: wifi_ul,
            cellular_download_mbps: cell_dl,
            cellular_upload_mbps: cell_ul,
            combined_download_mbps: combined_dl,
            combined_upload_mbps: combined_ul,
            wifi_rtt_ms: wifi_rtt,
            cellular_rtt_ms: cell_rtt,
            combined_rtt_ms: combined_rtt,
            gain_percent,
            duration_ms: overall_start.elapsed().as_millis() as u64,
        })
    }

    async fn run_phase(
        &self,
        targets: &[(u8, Arc<UdpSocket>)],
        server_addr: SocketAddr,
        session_id: u64,
        cipher: Arc<AeadCipher>,
        duration_ms: u64,
    ) -> (f64, f64, f64, bool) {
        let phase_start = Instant::now();
        let deadline = phase_start + Duration::from_millis(duration_ms);
        let rx_done = Arc::new(AtomicBool::new(false));
        let rx_bytes = Arc::new(AtomicU64::new(0));
        let tx_bytes = Arc::new(AtomicU64::new(0));
        let rx_pkts = Arc::new(AtomicU64::new(0));
        let rtt_sum_us = Arc::new(AtomicU64::new(0));

        // Spawn concurrent RX echo collector per socket
        let mut rx_handles = Vec::with_capacity(targets.len());
        for (_pid, sock) in targets {
            let sock_c = Arc::clone(sock);
            let cipher_c = Arc::clone(&cipher);
            let rx_done_c = Arc::clone(&rx_done);
            let rx_bytes_c = Arc::clone(&rx_bytes);
            let rx_pkts_c = Arc::clone(&rx_pkts);
            let rtt_sum_c = Arc::clone(&rtt_sum_us);
            let running_c = Arc::clone(&self.running);

            rx_handles.push(tokio::spawn(async move {
                let mut buf = [0u8; 2048];
                while running_c.load(Ordering::Relaxed) && !rx_done_c.load(Ordering::Relaxed) {
                    match tokio::time::timeout(
                        Duration::from_millis(100),
                        sock_c.recv_from(&mut buf),
                    )
                    .await
                    {
                        Ok(Ok((len, _))) if len >= HEADER_SIZE + TAG_SIZE => {
                            if let Ok(hdr) = Vl01Header::decode(&buf[..HEADER_SIZE]) {
                                if hdr.session_id == session_id && hdr.is_speedtest() {
                                    let mut hdr_copy = [0u8; HEADER_SIZE];
                                    hdr_copy.copy_from_slice(&buf[..HEADER_SIZE]);
                                    if let Ok(plain_len) = cipher_c.decrypt_in_place(
                                        Direction::ServerToClient,
                                        hdr.path_id,
                                        hdr.seq_num,
                                        &hdr_copy,
                                        &mut buf[HEADER_SIZE..len],
                                        len - HEADER_SIZE,
                                    ) {
                                        let now_rel = (phase_start.elapsed().as_micros()
                                            & 0xFFFF_FFFF)
                                            as u32;
                                        let rtt_us = now_rel.wrapping_sub(hdr.tx_timestamp_us);
                                        if rtt_us > 0 && rtt_us < 5_000_000 {
                                            rtt_sum_c.fetch_add(rtt_us as u64, Ordering::Relaxed);
                                        }
                                        rx_bytes_c.fetch_add(
                                            (HEADER_SIZE + plain_len) as u64,
                                            Ordering::Relaxed,
                                        );
                                        rx_pkts_c.fetch_add(1, Ordering::Relaxed);
                                    }
                                }
                            }
                        }
                        Ok(Err(_)) => tokio::time::sleep(Duration::from_millis(5)).await,
                        _ => {}
                    }
                }
            }));
        }

        // Burst-paced TX loop across target sockets
        let payload = [0xAAu8; 1100];
        let mut seq: u64 = 1;
        let mut packet = [0u8; HEADER_SIZE + 1100 + TAG_SIZE];
        let mut hdr_bytes = [0u8; HEADER_SIZE];

        while Instant::now() < deadline && self.running.load(Ordering::SeqCst) {
            for (path_id, sock) in targets {
                let mut header =
                    Vl01Header::new(flags::DATA | flags::SPEEDTEST, *path_id, session_id, seq);
                header.tx_timestamp_us =
                    (phase_start.elapsed().as_micros() & 0xFFFF_FFFF) as u32;

                if header.encode(&mut hdr_bytes).is_ok() {
                    packet[..HEADER_SIZE].copy_from_slice(&hdr_bytes);
                    packet[HEADER_SIZE..HEADER_SIZE + payload.len()].copy_from_slice(&payload);

                    if let Ok(enc_len) = cipher.encrypt_in_place(
                        Direction::ClientToServer,
                        *path_id,
                        seq,
                        &hdr_bytes,
                        &mut packet[HEADER_SIZE..],
                        payload.len(),
                    ) {
                        let total_len = HEADER_SIZE + enc_len;
                        if let Ok(sent) = sock.send_to(&packet[..total_len], server_addr).await {
                            tx_bytes.fetch_add(sent as u64, Ordering::Relaxed);
                        }
                    }
                }
                seq += 1;
            }
            tokio::time::sleep(Duration::from_micros(600)).await;
        }

        // Allow up to 250ms for final in-flight echoes to arrive
        let drain_deadline = Instant::now() + Duration::from_millis(250);
        while Instant::now() < drain_deadline
            && rx_bytes.load(Ordering::Relaxed) < tx_bytes.load(Ordering::Relaxed)
        {
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        rx_done.store(true, Ordering::SeqCst);
        for handle in rx_handles {
            let _ = handle.await;
        }

        let count = rx_pkts.load(Ordering::Relaxed);
        if count == 0 {
            return (0.0, 0.0, 0.0, false);
        }

        let elapsed_sec = phase_start.elapsed().as_secs_f64().max(0.1);
        let dl_mbps = (rx_bytes.load(Ordering::Relaxed) as f64 * 8.0) / (elapsed_sec * 1_000_000.0);
        // Only credit upload goodput proportional to acknowledged/echoed rate so local send buffer doesn't inflate it
        let echoed_ratio = (rx_bytes.load(Ordering::Relaxed) as f64
            / tx_bytes.load(Ordering::Relaxed).max(1) as f64)
            .clamp(0.0, 1.0);
        let ul_mbps = ((tx_bytes.load(Ordering::Relaxed) as f64 * echoed_ratio) * 8.0)
            / (elapsed_sec * 1_000_000.0);
        let avg_rtt_ms = (rtt_sum_us.load(Ordering::Relaxed) as f64 / count as f64) / 1000.0;

        (dl_mbps, ul_mbps, avg_rtt_ms, true)
    }
}
