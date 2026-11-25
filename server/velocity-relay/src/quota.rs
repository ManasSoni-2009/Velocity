use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use tracing::{info, warn};

pub const DEFAULT_ORACLE_LIMIT_GB: u64 = 10_240; // 10 TiB Always Free OCI monthly outbound egress
const BYTES_PER_GIB: u64 = 1024 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct QuotaSnapshot {
    pub provider: String,
    pub period: String,
    pub used_bytes: u64,
    pub client_egress_bytes: u64,
    pub wan_egress_bytes: u64,
    pub limit_bytes: u64,
    pub remaining_bytes: u64,
    pub percent_used: f64,
    pub active_sessions: usize,
    pub reset_at_epoch_ms: u64,
    pub quota_exceeded: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedQuota {
    period_ym: u32,
    client_egress_bytes: u64,
    wan_egress_bytes: u64,
}

/// Lock-free atomic monthly egress tracker with UTC month rollover & optional disk persistence.
pub struct QuotaTracker {
    provider: String,
    limit_bytes: u64,
    enforce_limit: bool,
    usage_file: Option<PathBuf>,
    period_ym: AtomicU64, // year * 100 + month (e.g. 202611)
    client_egress_bytes: AtomicU64,
    wan_egress_bytes: AtomicU64,
    rollover_lock: Mutex<()>,
    exceeded_logged: AtomicBool,
}

impl QuotaTracker {
    pub fn new(
        provider: String,
        monthly_limit_gb: u64,
        enforce_limit: bool,
        usage_file: Option<PathBuf>,
    ) -> Self {
        let limit_bytes = monthly_limit_gb.saturating_mul(BYTES_PER_GIB);
        let (ym, _, _) = current_utc_ym_and_reset();

        let mut initial_client = 0u64;
        let mut initial_wan = 0u64;

        if let Some(ref path) = usage_file {
            if let Ok(raw) = fs::read_to_string(path) {
                if let Ok(saved) = serde_json::from_str::<PersistedQuota>(&raw) {
                    if saved.period_ym == ym {
                        initial_client = saved.client_egress_bytes;
                        initial_wan = saved.wan_egress_bytes;
                        info!(
                            "Loaded monthly quota usage for {}: client={} bytes, wan={} bytes",
                            format_ym(ym),
                            initial_client,
                            initial_wan
                        );
                    } else {
                        info!(
                            "New UTC billing month {}; resetting prior usage from {}",
                            format_ym(ym),
                            format_ym(saved.period_ym)
                        );
                    }
                }
            }
        }

        Self {
            provider,
            limit_bytes,
            enforce_limit,
            usage_file,
            period_ym: AtomicU64::new(ym as u64),
            client_egress_bytes: AtomicU64::new(initial_client),
            wan_egress_bytes: AtomicU64::new(initial_wan),
            rollover_lock: Mutex::new(()),
            exceeded_logged: AtomicBool::new(false),
        }
    }

    pub fn record_client_egress(&self, bytes: usize) {
        self.client_egress_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub fn record_wan_egress(&self, bytes: usize) {
        self.wan_egress_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub fn used_bytes(&self) -> u64 {
        self.client_egress_bytes
            .load(Ordering::Relaxed)
            .saturating_add(self.wan_egress_bytes.load(Ordering::Relaxed))
    }

    pub fn is_quota_blocked(&self) -> bool {
        if !self.enforce_limit || self.limit_bytes == 0 {
            return false;
        }
        let blocked = self.used_bytes() >= self.limit_bytes;
        if blocked && !self.exceeded_logged.swap(true, Ordering::Relaxed) {
            warn!(
                "Monthly outbound quota exceeded ({} / {} bytes); throttling forwarding",
                self.used_bytes(),
                self.limit_bytes
            );
        }
        blocked
    }

    /// Check UTC calendar month rollover and persist usage if backed by a file.
    pub fn tick_rollover_and_persist(&self) {
        let (now_ym, _, _) = current_utc_ym_and_reset();
        let stored = self.period_ym.load(Ordering::Relaxed) as u32;
        if now_ym != stored {
            let _guard = self.rollover_lock.lock();
            if self.period_ym.load(Ordering::Relaxed) as u32 != now_ym {
                self.client_egress_bytes.store(0, Ordering::Relaxed);
                self.wan_egress_bytes.store(0, Ordering::Relaxed);
                self.exceeded_logged.store(false, Ordering::Relaxed);
                self.period_ym.store(now_ym as u64, Ordering::Release);
                info!("UTC month rolled over to {}; quota counters reset", format_ym(now_ym));
            }
        }
        self.persist();
    }

    pub fn persist(&self) {
        let Some(ref path) = self.usage_file else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let payload = PersistedQuota {
            period_ym: self.period_ym.load(Ordering::Relaxed) as u32,
            client_egress_bytes: self.client_egress_bytes.load(Ordering::Relaxed),
            wan_egress_bytes: self.wan_egress_bytes.load(Ordering::Relaxed),
        };
        if let Ok(json) = serde_json::to_string_pretty(&payload) {
            let tmp = path.with_extension("json.tmp");
            if fs::write(&tmp, json).is_ok() {
                let _ = fs::rename(tmp, path);
            }
        }
    }

    pub fn snapshot(&self, active_sessions: usize) -> QuotaSnapshot {
        let (ym, period_str, reset_ms) = current_utc_ym_and_reset();
        if ym != self.period_ym.load(Ordering::Relaxed) as u32 {
            self.tick_rollover_and_persist();
        }
        let client = self.client_egress_bytes.load(Ordering::Relaxed);
        let wan = self.wan_egress_bytes.load(Ordering::Relaxed);
        let used = client.saturating_add(wan);
        let remaining = self.limit_bytes.saturating_sub(used);
        let percent = if self.limit_bytes > 0 {
            ((used as f64 / self.limit_bytes as f64) * 100.0).min(100.0)
        } else {
            0.0
        };

        QuotaSnapshot {
            provider: self.provider.clone(),
            period: period_str,
            used_bytes: used,
            client_egress_bytes: client,
            wan_egress_bytes: wan,
            limit_bytes: self.limit_bytes,
            remaining_bytes: remaining,
            percent_used: (percent * 100.0).round() / 100.0,
            active_sessions,
            reset_at_epoch_ms: reset_ms,
            quota_exceeded: self.limit_bytes > 0 && used >= self.limit_bytes,
        }
    }
}

fn format_ym(ym: u32) -> String {
    let year = ym / 100;
    let month = ym % 100;
    format!("{:04}-{:02}", year, month)
}

/// Pure-std UTC YYYY-MM & next month's midnight epoch ms using Hinnant's civil calendar algorithm.
pub fn current_utc_ym_and_reset() -> (u32, String, u64) {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let days = (secs / 86_400) as i64;
    let (year, month, _) = civil_from_days(days);
    let ym = (year as u32) * 100 + month;
    let period = format!("{:04}-{:02}", year, month);

    let (next_year, next_month) = if month == 12 {
        (year + 1, 1)
    } else {
        (year, month + 1)
    };
    let next_days = days_from_civil(next_year, next_month, 1);
    let reset_ms = (next_days.max(0) as u64) * 86_400 * 1000;
    (ym, period, reset_ms)
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = (yoe as i64) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    let year = y + if m <= 2 { 1 } else { 0 };
    (year, m, d)
}

fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = y - if m <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as u64;
    let m_adj = if m > 2 { m - 3 } else { m + 9 } as u64;
    let doy = (153 * m_adj + 2) / 5 + (d as u64) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + (doe as i64) - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_quota_accounting_and_enforcement() {
        let tracker = QuotaTracker::new(
            "Oracle Cloud Free".to_string(),
            1, // 1 GiB
            true,
            None,
        );
        assert!(!tracker.is_quota_blocked());
        tracker.record_client_egress(500 * 1024 * 1024);
        tracker.record_wan_egress(200 * 1024 * 1024);
        let snap = tracker.snapshot(3);
        assert_eq!(snap.active_sessions, 3);
        assert_eq!(snap.used_bytes, 700 * 1024 * 1024);
        assert!(!snap.quota_exceeded);
        assert!(snap.percent_used > 68.0 && snap.percent_used < 69.0);
        assert!(snap.reset_at_epoch_ms > 0);

        tracker.record_client_egress(400 * 1024 * 1024);
        assert!(tracker.is_quota_blocked());
        assert!(tracker.snapshot(3).quota_exceeded);
    }
}
