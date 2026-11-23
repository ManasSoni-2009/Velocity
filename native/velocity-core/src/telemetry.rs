use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LinkTelemetry {
    pub link_type: i32, // 0 = Wi-Fi, 1 = Cellular
    pub name: String,
    pub is_active: bool,
    pub is_metered: bool,
    pub mbps_down: f64,
    pub mbps_up: f64,
    pub rtt_ms: f64,
    pub loss_pct: f64,
    pub jitter_ms: f64,
    pub scheduler_weight_pct: f64,
    pub signal_dbm: i32,
    pub link_speed_mbps: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CombinedTelemetry {
    pub wifi: LinkTelemetry,
    pub cellular: LinkTelemetry,
    pub combined_mbps_down: f64,
    pub combined_mbps_up: f64,
    pub combined_rtt_ms: f64,
    pub aggregation_efficiency_pct: f64,
    pub active_mode: i32,
    pub connection_state: i32,
    pub fec_recovered_packets: u64,
    pub reordered_packets: u64,
    pub lost_packets: u64,
    pub timestamp_epoch_ms: u64,
}

impl Default for CombinedTelemetry {
    fn default() -> Self {
        Self {
            wifi: LinkTelemetry {
                link_type: 0,
                name: "Wi-Fi".to_string(),
                is_active: false,
                is_metered: false,
                mbps_down: 0.0,
                mbps_up: 0.0,
                rtt_ms: 0.0,
                loss_pct: 0.0,
                jitter_ms: 0.0,
                scheduler_weight_pct: 0.0,
                signal_dbm: -50,
                link_speed_mbps: 0,
            },
            cellular: LinkTelemetry {
                link_type: 1,
                name: "Cellular".to_string(),
                is_active: false,
                is_metered: true,
                mbps_down: 0.0,
                mbps_up: 0.0,
                rtt_ms: 0.0,
                loss_pct: 0.0,
                jitter_ms: 0.0,
                scheduler_weight_pct: 0.0,
                signal_dbm: -75,
                link_speed_mbps: 0,
            },
            combined_mbps_down: 0.0,
            combined_mbps_up: 0.0,
            combined_rtt_ms: 0.0,
            aggregation_efficiency_pct: 0.0,
            active_mode: 0,
            connection_state: 0,
            fec_recovered_packets: 0,
            reordered_packets: 0,
            lost_packets: 0,
            timestamp_epoch_ms: 0,
        }
    }
}
