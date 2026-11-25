use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum BondingMode {
    Speed = 0,
    LowLatency = 1,
    Failover = 2,
}

impl From<u8> for BondingMode {
    fn from(val: u8) -> Self {
        match val {
            0 => BondingMode::Speed,
            1 => BondingMode::LowLatency,
            2 => BondingMode::Failover,
            _ => BondingMode::Speed,
        }
    }
}

impl From<i32> for BondingMode {
    fn from(val: i32) -> Self {
        (val as u8).into()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunnelConfig {
    #[serde(alias = "serverHost")]
    pub server_host: String,
    #[serde(alias = "serverPort")]
    pub server_port: u16,
    #[serde(alias = "psk_hex", alias = "pskHex")]
    pub psk: String,
    #[serde(default, alias = "sessionId")]
    pub session_id: Option<u64>,
    #[serde(default = "default_mtu")]
    pub mtu: usize,
    #[serde(default)]
    pub mode: u8,
    #[serde(default = "default_true", alias = "fecEnabled")]
    pub fec_enabled: bool,
    #[serde(default = "default_true", alias = "directDnsGuard")]
    pub direct_dns_guard: bool,
    #[serde(default = "default_wifi_bias", alias = "wifiBiasPercent")]
    pub wifi_bias_percent: u8,
    #[serde(default = "default_reorder_timeout_ms", alias = "minReorderTimeoutMs")]
    pub min_reorder_timeout_ms: u64,
    #[serde(default = "default_max_reorder_timeout_ms", alias = "maxReorderTimeoutMs")]
    pub max_reorder_timeout_ms: u64,
}

fn default_mtu() -> usize {
    1380
}

fn default_true() -> bool {
    true
}

fn default_wifi_bias() -> u8 {
    70
}

fn default_reorder_timeout_ms() -> u64 {
    15
}

fn default_max_reorder_timeout_ms() -> u64 {
    120
}

impl Default for TunnelConfig {
    fn default() -> Self {
        Self {
            server_host: "127.0.0.1".to_string(),
            server_port: 51820,
            psk: "velocity-secret-bonding-key".to_string(),
            session_id: None,
            mtu: default_mtu(),
            mode: 0,
            fec_enabled: true,
            direct_dns_guard: true,
            wifi_bias_percent: default_wifi_bias(),
            min_reorder_timeout_ms: default_reorder_timeout_ms(),
            max_reorder_timeout_ms: default_max_reorder_timeout_ms(),
        }
    }
}
