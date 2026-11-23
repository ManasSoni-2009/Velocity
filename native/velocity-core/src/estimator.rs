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
    pub consecutive_timeouts: u32,
    pub last_seen: Instant,
    first_sample: bool,
}

impl PathEstimator {
    pub fn new(path_id: u8, name: String, is_metered: bool) -> Self {
        Self {
            path_id,
            name,
            is_metered,
            is_active: true,
            srtt_us: 20_000,     // 20ms default initial estimate
            min_rtt_us: 20_000,
            jitter_us: 5_000,
            loss_rate: 0.0,
            delivery_rate_bps: 10_000_000, // 10 Mbps default initial estimate
            in_flight_bytes: 0,
            rssi_dbm: -50,
            link_speed_mbps: 100,
            packets_sent: 0,
            packets_acked: 0,
            packets_lost: 0,
            bytes_sent: 0,
            bytes_acked: 0,
            consecutive_timeouts: 0,
            last_seen: Instant::now(),
            first_sample: true,
        }
    }

    /// Update RTT with a new sample using RFC 6298 EWMA
    pub fn record_rtt_sample(&mut self, sample_us: u32) {
        if sample_us == 0 || sample_us > 2_000_000 {
            // Ignore outliers (> 2 seconds or 0)
            return;
        }

        self.last_seen = Instant::now();
        self.consecutive_timeouts = 0;

        if self.first_sample {
            self.srtt_us = sample_us;
            self.jitter_us = sample_us / 2;
            self.min_rtt_us = sample_us;
            self.first_sample = false;
        } else {
            let delta = if sample_us > self.srtt_us {
                sample_us - self.srtt_us
            } else {
                self.srtt_us - sample_us
            };

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

    /// Record newly acknowledged bytes and update delivery rate
    pub fn record_ack(&mut self, bytes_acked: usize, interval_us: u64) {
        self.packets_acked += 1;
        self.bytes_acked += bytes_acked as u64;
        self.in_flight_bytes = self.in_flight_bytes.saturating_sub(bytes_acked);
        self.last_seen = Instant::now();
        self.consecutive_timeouts = 0;

        if interval_us > 100 {
            let instantaneous_bps = (bytes_acked as u64 * 8 * 1_000_000) / interval_us;
            if instantaneous_bps > 10_000 {
                // EWMA smoothing of delivery rate (weight 0.25 on new rate)
                self.delivery_rate_bps = (self.delivery_rate_bps * 3 + instantaneous_bps) / 4;
            }
        }

        self.update_loss_rate();
    }

    pub fn record_sent(&mut self, bytes: usize) {
        self.packets_sent += 1;
        self.bytes_sent += bytes as u64;
        self.in_flight_bytes += bytes;
    }

    pub fn record_loss(&mut self, bytes: usize) {
        self.packets_lost += 1;
        self.in_flight_bytes = self.in_flight_bytes.saturating_sub(bytes);
        self.consecutive_timeouts += 1;
        self.update_loss_rate();
    }

    fn update_loss_rate(&mut self) {
        let total = self.packets_acked + self.packets_lost;
        if total > 20 {
            self.loss_rate = (self.packets_lost as f32) / (total as f32);
        }
    }

    /// Bandwidth-Delay Product in bytes
    pub fn bdp_bytes(&self) -> usize {
        let bdp = (self.delivery_rate_bps as f64 / 8.0) * (self.min_rtt_us as f64 / 1_000_000.0);
        (bdp as usize).max(16_384) // min 16 KB window
    }

    /// Check if path is currently considered alive
    pub fn is_alive(&self) -> bool {
        self.is_active && self.consecutive_timeouts < 5 && self.last_seen.elapsed().as_millis() < 500
    }
}
