use serde::{Deserialize, Serialize};

/// Per-link live telemetry snapshot serialized identically to Kotlin's `LinkTelemetry`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkTelemetry {
    pub path_id: i32,
    #[serde(rename = "type")]
    pub link_type: String, // "WIFI" or "CELLULAR"
    pub interface_name: String,
    pub active: bool,
    pub has_internet: bool,
    pub is_metered: bool,
    pub download_mbps: f64,
    pub upload_mbps: f64,
    pub rtt_ms: f64,
    pub min_rtt_ms: f64,
    pub jitter_ms: f64,
    pub loss_percent: f64,
    pub scheduler_weight_percent: i32,
    pub signal_dbm: i32,
    pub link_speed_mbps: i32,
    pub bytes_rx: u64,
    pub bytes_tx: u64,
}

/// Global relay monthly egress quota reported by Oracle Cloud / self-hosted relays.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RelayQuotaInfo {
    #[serde(default)]
    pub provider: String,
    #[serde(default)]
    pub period: String,
    #[serde(default)]
    pub used_bytes: u64,
    #[serde(default)]
    pub limit_bytes: u64,
    #[serde(default)]
    pub remaining_bytes: u64,
    #[serde(default)]
    pub percent_used: f64,
    #[serde(default)]
    pub active_sessions: usize,
    #[serde(default)]
    pub reset_at_epoch_ms: u64,
    #[serde(default)]
    pub quota_exceeded: bool,
}

/// Optional JSON envelope returned inside encrypted `CONTROL_PROBE` replies.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeReplyPayload {
    #[serde(default)]
    pub ok: bool,
    #[serde(default)]
    pub quota: Option<RelayQuotaInfo>,
}

/// Aggregate multipath session telemetry serialized identically to Kotlin's `CombinedTelemetry`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CombinedTelemetry {
    pub timestamp_ms: u64,
    pub wifi: LinkTelemetry,
    pub cellular: LinkTelemetry,
    pub combined_download_mbps: f64,
    pub combined_upload_mbps: f64,
    pub effective_rtt_ms: f64,
    pub aggregation_efficiency_percent: i32,
    pub reorder_buffer_depth: usize,
    pub reorder_timeout_ms: u64,
    pub fec_recovered_packets: u64,
    pub fec_sent_packets: u64,
    pub duplicated_packets: u64,
    pub session_bytes_rx: u64,
    pub session_bytes_tx: u64,
    pub cellular_bytes_used_mb: f64,
    pub active_mode: i32,
    pub connection_state: i32,
    pub status_message: String,
    #[serde(default)]
    pub relay_quota: Option<RelayQuotaInfo>,
}

impl Default for CombinedTelemetry {
    fn default() -> Self {
        Self {
            timestamp_ms: 0,
            wifi: LinkTelemetry {
                path_id: 0,
                link_type: "WIFI".to_string(),
                interface_name: "wlan0".to_string(),
                active: false,
                has_internet: false,
                is_metered: false,
                download_mbps: 0.0,
                upload_mbps: 0.0,
                rtt_ms: 0.0,
                min_rtt_ms: 0.0,
                jitter_ms: 0.0,
                loss_percent: 0.0,
                scheduler_weight_percent: 0,
                signal_dbm: -120,
                link_speed_mbps: 0,
                bytes_rx: 0,
                bytes_tx: 0,
            },
            cellular: LinkTelemetry {
                path_id: 1,
                link_type: "CELLULAR".to_string(),
                interface_name: "rmnet0".to_string(),
                active: false,
                has_internet: false,
                is_metered: true,
                download_mbps: 0.0,
                upload_mbps: 0.0,
                rtt_ms: 0.0,
                min_rtt_ms: 0.0,
                jitter_ms: 0.0,
                loss_percent: 0.0,
                scheduler_weight_percent: 0,
                signal_dbm: -120,
                link_speed_mbps: 0,
                bytes_rx: 0,
                bytes_tx: 0,
            },
            combined_download_mbps: 0.0,
            combined_upload_mbps: 0.0,
            effective_rtt_ms: 0.0,
            aggregation_efficiency_percent: 0,
            reorder_buffer_depth: 0,
            reorder_timeout_ms: 15,
            fec_recovered_packets: 0,
            fec_sent_packets: 0,
            duplicated_packets: 0,
            session_bytes_rx: 0,
            session_bytes_tx: 0,
            cellular_bytes_used_mb: 0.0,
            active_mode: 0,
            connection_state: 0,
            status_message: "Ready to bond".to_string(),
            relay_quota: None,
        }
    }
}
