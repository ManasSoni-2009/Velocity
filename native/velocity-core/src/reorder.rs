use std::collections::BTreeMap;
use std::time::{Duration, Instant};

pub const FEC_BLOCK_SIZE: usize = 4;

#[derive(Debug)]
struct BufferedPacket {
    arrived_at: Instant,
    payload: Vec<u8>,
}

#[derive(Debug, Default)]
struct FecBlockTracker {
    packets: [Option<Vec<u8>>; FEC_BLOCK_SIZE],
    parity: Option<Vec<u8>>,
}

pub struct ReorderBuffer {
    pub next_expected_seq: u64,
    buffer: BTreeMap<u64, BufferedPacket>,
    fec_blocks: BTreeMap<u64, FecBlockTracker>,
    max_capacity: usize,

    // Telemetry & stats
    pub reordered_packets: u64,
    pub fec_recovered_packets: u64,
    pub dropped_duplicates: u64,
    pub timed_out_gaps: u64,

    gap_timer: Option<Instant>,
}

impl Default for ReorderBuffer {
    fn default() -> Self {
        Self::new(2048)
    }
}

impl ReorderBuffer {
    pub fn new(max_capacity: usize) -> Self {
        Self {
            next_expected_seq: 0,
            buffer: BTreeMap::new(),
            fec_blocks: BTreeMap::new(),
            max_capacity,
            reordered_packets: 0,
            fec_recovered_packets: 0,
            dropped_duplicates: 0,
            timed_out_gaps: 0,
            gap_timer: None,
        }
    }

    /// Calculate adaptive reorder timeout: clamp(1.5 * |RTT_0 - RTT_1| + 10ms, 15ms, 120ms)
    pub fn adaptive_timeout(rtt0_us: u32, rtt1_us: u32) -> Duration {
        let rtt0_ms = rtt0_us as f64 / 1000.0;
        let rtt1_ms = rtt1_us as f64 / 1000.0;
        let diff_ms = (rtt0_ms - rtt1_ms).abs();

        let timeout_ms = (1.5 * diff_ms + 10.0).clamp(15.0, 120.0);
        Duration::from_millis(timeout_ms as u64)
    }

    /// Reset sequence number tracking to a specific baseline
    pub fn reset_seq(&mut self, seq_num: u64) {
        self.next_expected_seq = seq_num;
        self.buffer.clear();
        self.fec_blocks.clear();
        self.gap_timer = None;
    }

    /// Insert an incoming packet and return any packets ready for delivery in-order
    pub fn push(
        &mut self,
        seq_num: u64,
        is_fec: bool,
        is_duplicate: bool,
        payload: &[u8],
        rtt0_us: u32,
        rtt1_us: u32,
    ) -> Vec<Vec<u8>> {
        let mut ready = Vec::new();

        // Handle initial sequence setup
        if self.next_expected_seq == 0 && self.buffer.is_empty() {
            self.next_expected_seq = seq_num;
        }

        // Duplicate or older than window
        if seq_num < self.next_expected_seq {
            self.dropped_duplicates += 1;
            return ready;
        }

        // If duplicate flag is set and we already processed this seq
        if is_duplicate && self.buffer.contains_key(&seq_num) {
            self.dropped_duplicates += 1;
            return ready;
        }

        if is_fec {
            // Track FEC parity packet
            let block_base = (seq_num / FEC_BLOCK_SIZE as u64) * FEC_BLOCK_SIZE as u64;
            let tracker = self.fec_blocks.entry(block_base).or_default();
            tracker.parity = Some(payload.to_vec());
            self.try_fec_recovery(block_base);
            return self.drain_consecutive();
        }

        // Track regular packet in FEC block
        let block_base = (seq_num / FEC_BLOCK_SIZE as u64) * FEC_BLOCK_SIZE as u64;
        let offset = (seq_num - block_base) as usize;
        if offset < FEC_BLOCK_SIZE {
            let tracker = self.fec_blocks.entry(block_base).or_default();
            tracker.packets[offset] = Some(payload.to_vec());
            self.try_fec_recovery(block_base);
        }

        if seq_num == self.next_expected_seq {
            // Immediate in-order delivery
            ready.push(payload.to_vec());
            self.next_expected_seq += 1;
            self.gap_timer = None;

            // Drain any consecutive packets queued behind this one
            ready.extend(self.drain_consecutive());
        } else {
            // Out of order: buffer packet
            if self.buffer.len() < self.max_capacity {
                self.reordered_packets += 1;
                self.buffer.insert(
                    seq_num,
                    BufferedPacket {
                        arrived_at: Instant::now(),
                        payload: payload.to_vec(),
                    },
                );

                if self.gap_timer.is_none() {
                    self.gap_timer = Some(Instant::now());
                }
            } else {
                // Buffer overflow: force drain
                ready.extend(self.force_drain_on_overflow());
            }
        }

        // Clean up old FEC blocks
        if self.fec_blocks.len() > 32 {
            let cutoff = self.next_expected_seq.saturating_sub(16);
            self.fec_blocks.retain(|&base, _| base >= cutoff);
        }

        ready
    }

    /// Try to recover a single missing packet in an FEC block
    fn try_fec_recovery(&mut self, block_base: u64) {
        let tracker = match self.fec_blocks.get(&block_base) {
            Some(t) => t,
            None => return,
        };

        let parity = match &tracker.parity {
            Some(p) => p,
            None => return,
        };

        let mut missing_idx = None;
        let mut missing_count = 0;

        for i in 0..FEC_BLOCK_SIZE {
            let seq = block_base + i as u64;
            if seq >= self.next_expected_seq {
                if tracker.packets[i].is_none() && !self.buffer.contains_key(&seq) {
                    missing_idx = Some(i);
                    missing_count += 1;
                }
            }
        }

        // Exactly one packet missing: reconstruct via XOR
        if missing_count == 1 {
            let missing = missing_idx.unwrap();
            let missing_seq = block_base + missing as u64;

            let mut recovered = parity.clone();
            for i in 0..FEC_BLOCK_SIZE {
                if i != missing {
                    if let Some(pkt) = &tracker.packets[i] {
                        for (b_idx, byte) in pkt.iter().enumerate() {
                            if b_idx < recovered.len() {
                                recovered[b_idx] ^= *byte;
                            }
                        }
                    }
                }
            }

            self.fec_recovered_packets += 1;
            self.buffer.insert(
                missing_seq,
                BufferedPacket {
                    arrived_at: Instant::now(),
                    payload: recovered,
                },
            );
        }
    }

    /// Flush any gaps whose adaptive timeout has expired
    pub fn check_timeouts(&mut self, rtt0_us: u32, rtt1_us: u32) -> Vec<Vec<u8>> {
        let timeout = Self::adaptive_timeout(rtt0_us, rtt1_us);

        if let Some(start) = self.gap_timer {
            if start.elapsed() >= timeout {
                // Gap expired: skip missing packet and drain
                self.timed_out_gaps += 1;
                self.gap_timer = None;

                if let Some((&first_seq, _)) = self.buffer.iter().next() {
                    self.next_expected_seq = first_seq;
                } else {
                    self.next_expected_seq += 1;
                }

                return self.drain_consecutive();
            }
        }
        Vec::new()
    }

    fn drain_consecutive(&mut self) -> Vec<Vec<u8>> {
        let mut ready = Vec::new();
        while let Some(pkt) = self.buffer.remove(&self.next_expected_seq) {
            ready.push(pkt.payload);
            self.next_expected_seq += 1;
        }

        if self.buffer.is_empty() {
            self.gap_timer = None;
        } else if self.gap_timer.is_none() {
            self.gap_timer = Some(Instant::now());
        }

        ready
    }

    fn force_drain_on_overflow(&mut self) -> Vec<Vec<u8>> {
        let mut ready = Vec::new();
        if let Some((&min_seq, _)) = self.buffer.iter().next() {
            self.next_expected_seq = min_seq;
            ready.extend(self.drain_consecutive());
        }
        ready
    }
}
