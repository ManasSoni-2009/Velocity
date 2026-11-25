#[cfg(target_os = "linux")]
use std::process::Command;
#[cfg(target_os = "linux")]
use tracing::info;
#[cfg(not(target_os = "linux"))]
use tracing::warn;

pub struct NatHelper;

impl NatHelper {
    /// Configure IP forwarding, Oracle Cloud-safe firewall rules, MSS clamping, and MASQUERADE NAT.
    pub fn setup_nat(tun_name: &str, wan_interface: &str, subnet: &str) -> std::io::Result<()> {
        Self::setup_nat_with_port(tun_name, wan_interface, subnet, 51820)
    }

    pub fn setup_nat_with_port(
        #[allow(unused_variables)] tun_name: &str,
        #[allow(unused_variables)] wan_interface: &str,
        #[allow(unused_variables)] subnet: &str,
        #[allow(unused_variables)] udp_port: u16,
    ) -> std::io::Result<()> {
        #[cfg(target_os = "linux")]
        {
            info!("Enabling IPv4 forwarding and relaxed rp_filter for multipath NAT");
            let _ = Command::new("sysctl")
                .args(["-w", "net.ipv4.ip_forward=1"])
                .status();
            let _ = Command::new("sysctl")
                .args(["-w", "net.ipv4.conf.all.rp_filter=0"])
                .status();
            let _ = Command::new("sysctl")
                .args(["-w", "net.ipv4.conf.default.rp_filter=0"])
                .status();

            // Insert ahead of Oracle Cloud default REJECT rules in INPUT/FORWARD chains
            let port_str = udp_port.to_string();
            let _ = Command::new("iptables")
                .args(["-I", "INPUT", "1", "-p", "udp", "--dport", &port_str, "-j", "ACCEPT"])
                .status();

            info!("Configuring iptables NAT MASQUERADE for {} via {}", subnet, wan_interface);
            let _ = Command::new("iptables")
                .args(["-t", "nat", "-A", "POSTROUTING", "-s", subnet, "-o", wan_interface, "-j", "MASQUERADE"])
                .status();

            let _ = Command::new("iptables")
                .args(["-I", "FORWARD", "1", "-i", tun_name, "-o", wan_interface, "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-I", "FORWARD", "1", "-i", wan_interface, "-o", tun_name, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT"])
                .status();

            // Clamp TCP MSS so flows over MTU-1380 TUN never stall behind PMTU blackholes
            let _ = Command::new("iptables")
                .args(["-t", "mangle", "-A", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mtu-to-pmtu"])
                .status();

            info!("NAT + MSS clamping rules applied successfully");
        }
        #[cfg(not(target_os = "linux"))]
        {
            warn!("NAT helper is only supported on Linux; skipping host iptables rules");
        }

        Ok(())
    }

    /// Tear down iptables NAT & firewall rules on shutdown.
    pub fn teardown_nat(tun_name: &str, wan_interface: &str, subnet: &str) {
        Self::teardown_nat_with_port(tun_name, wan_interface, subnet, 51820);
    }

    pub fn teardown_nat_with_port(
        #[allow(unused_variables)] tun_name: &str,
        #[allow(unused_variables)] wan_interface: &str,
        #[allow(unused_variables)] subnet: &str,
        #[allow(unused_variables)] udp_port: u16,
    ) {
        #[cfg(target_os = "linux")]
        {
            let port_str = udp_port.to_string();
            let _ = Command::new("iptables")
                .args(["-D", "INPUT", "-p", "udp", "--dport", &port_str, "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-t", "nat", "-D", "POSTROUTING", "-s", subnet, "-o", wan_interface, "-j", "MASQUERADE"])
                .status();

            let _ = Command::new("iptables")
                .args(["-D", "FORWARD", "-i", tun_name, "-o", wan_interface, "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-D", "FORWARD", "-i", wan_interface, "-o", tun_name, "-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT"])
                .status();

            let _ = Command::new("iptables")
                .args(["-t", "mangle", "-D", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mtu-to-pmtu"])
                .status();
        }
    }
}
