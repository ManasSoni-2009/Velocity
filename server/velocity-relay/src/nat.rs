use std::process::Command;
use tracing::{info, warn};

pub struct NatHelper;

impl NatHelper {
    /// Configure IP forwarding and iptables NAT Masquerade on Linux host
    pub fn setup_nat(tun_name: &str, wan_interface: &str, subnet: &str) -> std::io::Result<()> {
        #[cfg(target_os = "linux")]
        {
            info!("Enabling IPv4 forwarding in /proc/sys/net/ipv4/ip_forward");
            let _ = Command::new("sysctl")
                .args(["-w", "net.ipv4.ip_forward=1"])
                .status();

            info!("Configuring iptables NAT MASQUERADE for {}", subnet);
            let _ = Command::new("iptables")
                .args(["-t", "nat", "-A", "POSTROUTING", "-s", subnet, "-o", wan_interface, "-j", "MASQUERADE"])
                .status();

            let _ = Command::new("iptables")
                .args(["-A", "FORWARD", "-i", tun_name, "-o", wan_interface, "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-A", "FORWARD", "-i", wan_interface, "-o", tun_name, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT"])
                .status();

            info!("NAT configuration applied successfully");
        }
        #[cfg(not(target_os = "linux"))]
        {
            warn!("NAT helper is only supported on Linux; skipping host iptables rules");
        }

        Ok(())
    }

    /// Tear down iptables NAT rules on shutdown
    pub fn teardown_nat(tun_name: &str, wan_interface: &str, subnet: &str) {
        #[cfg(target_os = "linux")]
        {
            let _ = Command::new("iptables")
                .args(["-t", "nat", "-D", "POSTROUTING", "-s", subnet, "-o", wan_interface, "-j", "MASQUERADE"])
                .status();

            let _ = Command::new("iptables")
                .args(["-D", "FORWARD", "-i", tun_name, "-o", wan_interface, "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-D", "FORWARD", "-i", wan_interface, "-o", tun_name, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT"])
                .status();
        }
    }
}
