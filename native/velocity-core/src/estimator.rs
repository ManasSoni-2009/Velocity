use std::time::Instant;

const ALPHA_NUM: u32 = 1;
const ALPHA_DEN: u32 = 8; // alpha = 1/8
const BETA_NUM: u32 = 1;
const BETA_DEN: u32 = 4; // beta = 1/4

#[derive(Debug, Clone)]
pub struct PathEstimator {
    pub path_id: u8,
    pub name: String,
    pub is_metered: bool,
    pub is_active: bool,

    // RTT & Jitter metrics in microseconds
    pub srtt_us: u32,
    pub min_rtt_us: u32,
    pub jitter_us: u32,

    // Delivery rate & Loss
    pub loss_rate: f32,
    pub delivery_rate_bps: u64,
    pub in_flight_bytes: usize,

    // Link telemetry
    pub rssi_dbm: i32,
    pub link_speed_mbps: i32,

    // Internal tracking
    pub packets_sent: u64,
    pub packets_acked: u64,
    pub packets_lost: u64,
    pub bytes_sent: u64,
    pub bytes_acked: u64,
    pub bytes_rx: u64,
    pub consecutive_timeouts: u32,
    pub last_seen: Instant,
    pub last_probe_sent: Option<Instant>,
    pub awaiting_probe: bool,
    pub verified_handshake: bool,

    // Sliding-window rate calculation
    pub rx_mbps: f64,
    pub tx_mbps: f64,
    pub window_bytes_rx: u64,
    pub window_bytes_tx: u64,
    pub window_bytes_scheduled: u64,
    pub last_rate_tick: Instant,
    first_sample: bool,
}

impl PathEstimator {
    pub fn new(path_id: u8, name: String, is_metered: bool) -> Self {
        let now = Instant::now();
        Self {
            path_id,
            name,
            is_metered,
            is_active: true,
            srtt_us: 20_000,     // 20ms initial scheduler prior before first sample
            min_rtt_us: 20_000,
            jitter_us: 5_000,
            loss_rate: 0.0,
            delivery_rate_bps: 10_000_000, // 10 Mbps initial scheduler prior
            in_flight_bytes: 0,
            rssi_dbm: -120,
            link_speed_mbps: 0,
            packets_sent: 0,
            packets_acked: 0,
            packets_lost: 0,
            bytes_sent: 0,
            bytes_acked: 0,
            bytes_rx: 0,
            consecutive_timeouts: 0,
            last_seen: now,
            last_probe_sent: None,
            awaiting_probe: false,
            verified_handshake: false,
            rx_mbps: 0.0,
            tx_mbps: 0.0,
            window_bytes_rx: 0,
            window_bytes_tx: 0,
            window_bytes_scheduled: 0,
            last_rate_tick: now,
            first_sample: true,
        }
    }

    /// Update RTT with a new sample using RFC 6298 EWMA.
    pub fn record_rtt_sample(&mut self, sample_us: u32) {
        if sample_us == 0 || sample_us > 5_000_000 {
            return;
        }

        self.last_seen = Instant::now();
        self.consecutive_timeouts = 0;
        self.awaiting_probe = false;
        self.verified_handshake = true;
        self.is_active = true;

        if self.first_sample {
            self.srtt_us = sample_us;
            self.jitter_us = sample_us / 4;
            self.min_rtt_us = sample_us;
            self.first_sample = false;
        } else {
            let delta = self.srtt_us.abs_diff(sample_us);

            // rttvar = (1 - beta) * rttvar + beta * delta
            self.jitter_us = ((BETA_DEN - BETA_NUM) * self.jitter_us + BETA_NUM * delta) / BETA_DEN;

            // srtt = (1 - alpha) * srtt + alpha * sample
            self.srtt_us =
                ((ALPHA_DEN - ALPHA_NUM) * self.srtt_us + ALPHA_NUM * sample_us) / ALPHA_DEN;

            if sample_us < self.min_rtt_us {
                self.min_rtt_us = sample_us;
            }
        }
    }

    /// Record newly acknowledged bytes and update delivery rate.
    pub fn record_ack(&mut self, bytes_acked: usize, interval_us: u64) {
        self.packets_acked += 1;
        self.bytes_acked += bytes_acked as u64;
        self.in_flight_bytes = self.in_flight_bytes.saturating_sub(bytes_acked);
        self.last_seen = Instant::now();
        self.consecutive_timeouts = 0;
        self.verified_handshake = true;

        if interval_us > 100 {
            let instantaneous_bps = (bytes_acked as u64 * 8 * 1_000_000) / interval_us;
            if instantaneous_bps > 10_000 {
                self.delivery_rate_bps = (self.delivery_rate_bps * 3 + instantaneous_bps) / 4;
            }
        }

        self.update_loss_rate();
    }

    pub fn record_sent(&mut self, bytes: usize) {
        self.packets_sent += 1;
        self.bytes_sent += bytes as u64;
        self.window_bytes_tx += bytes as u64;
        self.window_bytes_scheduled += bytes as u64;
        self.in_flight_bytes += bytes;
    }

    pub fn record_rx(&mut self, bytes: usize) {
        self.bytes_rx += bytes as u64;
        self.window_bytes_rx += bytes as u64;
        self.last_seen = Instant::now();
        self.consecutive_timeouts = 0;
        self.verified_handshake = true;
        self.is_active = true;
    }

    pub fn mark_probe_sent(&mut self) {
        self.last_probe_sent = Some(Instant::now());
        self.awaiting_probe = true;
    }

    pub fn check_probe_timeout(&mut self) {
        if let Some(sent_at) = self.last_probe_sent {
            if self.awaiting_probe && sent_at.elapsed().as_millis() >= 900 {
                self.awaiting_probe = false;
                self.record_loss(64);
            }
        }
    }

    pub fn record_loss(&mut self, bytes: usize) {
        self.packets_lost += 1;
        self.in_flight_bytes = self.in_flight_bytes.saturating_sub(bytes);
        self.consecutive_timeouts += 1;
        self.update_loss_rate();
    }

    /// Update sliding-window Rx/Tx throughput estimates in Mbps.
    pub fn sample_rates(&mut self) {
        let elapsed_s = self.last_rate_tick.elapsed().as_secs_f64();
        if elapsed_s < 0.05 {
            return;
        }
        let inst_rx_mbps = (self.window_bytes_rx as f64 * 8.0) / (elapsed_s * 1_000_000.0);
        let inst_tx_mbps = (self.window_bytes_tx as f64 * 8.0) / (elapsed_s * 1_000_000.0);

        self.rx_mbps = if inst_rx_mbps < 0.001 && self.rx_mbps < 0.05 {
            0.0
        } else {
            (self.rx_mbps * 0.5) + (inst_rx_mbps * 0.5)
        };
        self.tx_mbps = if inst_tx_mbps < 0.001 && self.tx_mbps < 0.05 {
            0.0
        } else {
            (self.tx_mbps * 0.5) + (inst_tx_mbps * 0.5)
        };

        let active_bps = (inst_rx_mbps.max(inst_tx_mbps) * 1_000_000.0) as u64;
        if active_bps > 100_000 {
            self.delivery_rate_bps = (self.delivery_rate_bps * 3 + active_bps) / 4;
        }

        self.window_bytes_rx = 0;
        self.window_bytes_tx = 0;
        self.last_rate_tick = Instant::now();
    }

    fn update_loss_rate(&mut self) {
        let total = self.packets_acked + self.packets_lost;
        if total > 10 {
            self.loss_rate = (self.packets_lost as f32) / (total as f32);
        }
    }

    /// Bandwidth-Delay Product in bytes.
    pub fn bdp_bytes(&self) -> usize {
        let bdp = (self.delivery_rate_bps as f64 / 8.0) * (self.min_rtt_us as f64 / 1_000_000.0);
        (bdp as usize).max(16_384) // min 16 KB window
    }

    /// True if path is active and has responded within the 1.5s liveness window.
    pub fn is_alive(&self) -> bool {
        self.is_active && self.consecutive_timeouts < 5 && self.last_seen.elapsed().as_millis() < 1500
    }

    /// True once at least one authenticated relay packet has returned over this path.
    pub fn has_verified_path(&self) -> bool {
        self.verified_handshake && self.is_alive()
    }
}
