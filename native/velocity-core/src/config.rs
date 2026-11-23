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
    pub server_host: String,
    pub server_port: u16,
    pub psk: String,
    pub session_id: Option<u64>,
    #[serde(default = "default_mtu")]
    pub mtu: usize,
    #[serde(default)]
    pub mode: u8,
    #[serde(default = "default_reorder_timeout_ms")]
    pub min_reorder_timeout_ms: u64,
    #[serde(default = "default_max_reorder_timeout_ms")]
    pub max_reorder_timeout_ms: u64,
}

fn default_mtu() -> usize {
    1380
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
            min_reorder_timeout_ms: default_reorder_timeout_ms(),
            max_reorder_timeout_ms: default_max_reorder_timeout_ms(),
        }
    }
}
