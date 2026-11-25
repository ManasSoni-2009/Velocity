use crate::config::BondingMode;
use crate::estimator::PathEstimator;
use std::time::{Duration, Instant};

pub const FEC_BLOCK_SIZE: usize = 4;
pub const KEEP_ALIVE_INTERVAL_MS: u64 = 250;
pub const FAILOVER_TIMEOUT_MS: u128 = 80;

#[derive(Debug)]
pub enum ScheduleDecision {
    SendSingle { path_id: u8 },
    SendDuplicate { primary_path: u8, secondary_path: u8 },
    SendWithFec { path_id: u8, parity_data: Vec<u8>, block_base_seq: u64 },
}

pub struct FecEncoder {
    block_base_seq: u64,
    accumulated: Vec<Vec<u8>>,
    max_len: usize,
}

impl Default for FecEncoder {
    fn default() -> Self {
        Self::new()
    }
}

impl FecEncoder {
    pub fn new() -> Self {
        Self {
            block_base_seq: 0,
            accumulated: Vec::with_capacity(FEC_BLOCK_SIZE),
            max_len: 0,
        }
    }

    /// Add a packet to the current FEC block.
    /// If block reaches FEC_BLOCK_SIZE, returns (block_base_seq, parity_bytes).
    pub fn push_packet(&mut self, seq_num: u64, payload: &[u8]) -> Option<(u64, Vec<u8>)> {
        if self.accumulated.is_empty() {
            self.block_base_seq = seq_num;
            self.max_len = 0;
        }

        self.max_len = self.max_len.max(payload.len());
        self.accumulated.push(payload.to_vec());

        if self.accumulated.len() >= FEC_BLOCK_SIZE {
            let mut parity = vec![0u8; self.max_len];
            for pkt in &self.accumulated {
                for (i, b) in pkt.iter().enumerate() {
                    parity[i] ^= *b;
                }
            }
            let base = self.block_base_seq;
            self.accumulated.clear();
            self.max_len = 0;
            Some((base, parity))
        } else {
            None
        }
    }
}

pub struct MultipathScheduler {
    pub mode: BondingMode,
    pub wifi_bias_percent: u8,
    pub fec_enabled: bool,
    pub fec_sent_packets: u64,
    pub duplicated_packets: u64,
    deficits: [i64; 2],
    fec_encoder: FecEncoder,
    last_keepalive: Instant,
    last_path0_healthy: Instant,
}

impl Default for MultipathScheduler {
    fn default() -> Self {
        Self::new(BondingMode::Speed)
    }
}

impl MultipathScheduler {
    pub fn new(mode: BondingMode) -> Self {
        Self {
            mode,
            wifi_bias_percent: 50,
            fec_enabled: true,
            fec_sent_packets: 0,
            duplicated_packets: 0,
            deficits: [0, 0],
            fec_encoder: FecEncoder::new(),
            last_keepalive: Instant::now(),
            last_path0_healthy: Instant::now(),
        }
    }

    pub fn set_mode(&mut self, mode: BondingMode) {
        self.mode = mode;
    }

    pub fn update_tuning(&mut self, wifi_bias_percent: u8, fec_enabled: bool) {
        self.wifi_bias_percent = wifi_bias_percent.min(100);
        self.fec_enabled = fec_enabled;
    }

    /// Check if a periodic keepalive probe should be sent on Cellular (Path 1).
    pub fn should_send_keepalive(&mut self) -> bool {
        if self.last_keepalive.elapsed() >= Duration::from_millis(KEEP_ALIVE_INTERVAL_MS) {
            self.last_keepalive = Instant::now();
            true
        } else {
            false
        }
    }

    /// Decide scheduling action for an outgoing packet.
    pub fn schedule(
        &mut self,
        packet: &[u8],
        seq_num: u64,
        paths: &[Option<PathEstimator>; 2],
    ) -> ScheduleDecision {
        let p0_opt = paths[0].as_ref();
        let p1_opt = paths[1].as_ref();

        match (p0_opt, p1_opt) {
            (Some(_), None) => ScheduleDecision::SendSingle { path_id: 0 },
            (None, Some(_)) => ScheduleDecision::SendSingle { path_id: 1 },
            (None, None) => ScheduleDecision::SendSingle { path_id: 0 },
            (Some(p0), Some(p1)) => self.schedule_dual(packet, seq_num, p0, p1),
        }
    }

    fn schedule_dual(
        &mut self,
        packet: &[u8],
        seq_num: u64,
        p0: &PathEstimator,
        p1: &PathEstimator,
    ) -> ScheduleDecision {
        match self.mode {
            BondingMode::Speed => self.schedule_speed(packet, p0, p1),
            BondingMode::LowLatency => self.schedule_low_latency(packet, seq_num, p0, p1),
            BondingMode::Failover => self.schedule_failover(p0, p1),
        }
    }

    /// Mode 0: Arrival-time-compensated BDP-weighted deficit round-robin.
    fn schedule_speed(
        &mut self,
        packet: &[u8],
        p0: &PathEstimator,
        p1: &PathEstimator,
    ) -> ScheduleDecision {
        let pkt_size = packet.len();

        if !p0.is_alive() && p1.is_alive() {
            return ScheduleDecision::SendSingle { path_id: 1 };
        }
        if !p1.is_alive() && p0.is_alive() {
            return ScheduleDecision::SendSingle { path_id: 0 };
        }
        if self.wifi_bias_percent >= 98 {
            return ScheduleDecision::SendSingle { path_id: 0 };
        }
        if self.wifi_bias_percent <= 2 {
            return ScheduleDecision::SendSingle { path_id: 1 };
        }

        let rate0 = p0.delivery_rate_bps.max(500_000) as f64;
        let rate1 = p1.delivery_rate_bps.max(500_000) as f64;

        let queue_delay0_us = ((p0.in_flight_bytes + pkt_size) as f64 * 8.0 * 1_000_000.0) / rate0;
        let queue_delay1_us = ((p1.in_flight_bytes + pkt_size) as f64 * 8.0 * 1_000_000.0) / rate1;

        let est_arrival0_us = (p0.srtt_us as f64 / 2.0) + queue_delay0_us;
        let est_arrival1_us = (p1.srtt_us as f64 / 2.0) + queue_delay1_us;

        let bdp0 = p0.bdp_bytes();
        let bdp1 = p1.bdp_bytes();

        let headroom0 = (bdp0 as isize) - (p0.in_flight_bytes as isize);
        let headroom1 = (bdp1 as isize) - (p1.in_flight_bytes as isize);

        // Apply user Wi-Fi bias smoothly around neutral 50%
        let bias0 = (self.wifi_bias_percent as f64 / 50.0).clamp(0.2, 1.8);
        let bias1 = ((100 - self.wifi_bias_percent) as f64 / 50.0).clamp(0.2, 1.8);

        let quantum0 = (((rate0 / 8.0) * 0.005) * bias0).clamp(1500.0, 48000.0) as i64;
        let quantum1 = (((rate1 / 8.0) * 0.005) * bias1).clamp(1500.0, 48000.0) as i64;

        if self.deficits[0] < (pkt_size as i64) && self.deficits[1] < (pkt_size as i64) {
            self.deficits[0] += quantum0;
            self.deficits[1] += quantum1;
        }

        let choose_path0 = if headroom0 <= 0 && headroom1 > 0 {
            false
        } else if headroom1 <= 0 && headroom0 > 0 {
            true
        } else if self.deficits[0] >= (pkt_size as i64) && self.deficits[1] < (pkt_size as i64) {
            true
        } else if self.deficits[1] >= (pkt_size as i64) && self.deficits[0] < (pkt_size as i64) {
            false
        } else if self.deficits[0] >= (pkt_size as i64) && self.deficits[1] >= (pkt_size as i64) {
            (self.deficits[0] as f64 / rate0) > (self.deficits[1] as f64 / rate1)
        } else {
            est_arrival0_us <= est_arrival1_us
        };

        if choose_path0 {
            self.deficits[0] -= pkt_size as i64;
            ScheduleDecision::SendSingle { path_id: 0 }
        } else {
            self.deficits[1] -= pkt_size as i64;
            ScheduleDecision::SendSingle { path_id: 1 }
        }
    }

    /// Mode 1: Min-RTT routing for interactive packets + adaptive XOR FEC / selective duplication.
    fn schedule_low_latency(
        &mut self,
        packet: &[u8],
        seq_num: u64,
        p0: &PathEstimator,
        p1: &PathEstimator,
    ) -> ScheduleDecision {
        if !p0.is_alive() && p1.is_alive() {
            return ScheduleDecision::SendSingle { path_id: 1 };
        }
        if !p1.is_alive() && p0.is_alive() {
            return ScheduleDecision::SendSingle { path_id: 0 };
        }

        let is_small = packet.len() < 256;
        let is_tcp = if packet.len() >= 40 && packet[0] >> 4 == 4 {
            packet[9] == 6
        } else {
            false
        };
        let is_interactive = is_small || is_tcp;

        let (best_path, other_path, best_loss, best_jitter) = if p0.srtt_us <= p1.srtt_us {
            (0, 1, p0.loss_rate, p0.jitter_us)
        } else {
            (1, 0, p1.loss_rate, p1.jitter_us)
        };

        let loss_spike = best_loss > 0.01 || best_jitter > 15_000;

        if is_interactive {
            if loss_spike && p0.is_alive() && p1.is_alive() {
                self.duplicated_packets += 1;
                ScheduleDecision::SendDuplicate {
                    primary_path: best_path,
                    secondary_path: other_path,
                }
            } else {
                ScheduleDecision::SendSingle { path_id: best_path }
            }
        } else if loss_spike && self.fec_enabled {
            if let Some((base_seq, parity)) = self.fec_encoder.push_packet(seq_num, packet) {
                self.fec_sent_packets += 1;
                ScheduleDecision::SendWithFec {
                    path_id: best_path,
                    parity_data: parity,
                    block_base_seq: base_seq,
                }
            } else {
                ScheduleDecision::SendSingle { path_id: best_path }
            }
        } else {
            ScheduleDecision::SendSingle { path_id: best_path }
        }
    }

    /// Mode 2: Primary Wi-Fi with <100ms failover to Cellular.
    fn schedule_failover(&mut self, p0: &PathEstimator, p1: &PathEstimator) -> ScheduleDecision {
        let now = Instant::now();
        if p0.is_alive() {
            self.last_path0_healthy = now;
            ScheduleDecision::SendSingle { path_id: 0 }
        } else if !p1.is_alive() {
            ScheduleDecision::SendSingle { path_id: 0 }
        } else if now.duration_since(self.last_path0_healthy).as_millis() > FAILOVER_TIMEOUT_MS {
            ScheduleDecision::SendSingle { path_id: 1 }
        } else {
            ScheduleDecision::SendSingle { path_id: 0 }
        }
    }
}
