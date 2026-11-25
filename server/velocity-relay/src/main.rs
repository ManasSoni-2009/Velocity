use clap::Parser;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::signal;
use tracing::info;
use velocity_relay::nat::NatHelper;
use velocity_relay::quota::{QuotaTracker, DEFAULT_ORACLE_LIMIT_GB};
use velocity_relay::server::{run_status_http_server, RelayServer};

#[derive(Parser, Debug)]
#[command(name = "velocity-relay")]
#[command(about = "Velocity multipath bonding relay server for Linux / Oracle Cloud", long_about = None)]
struct Args {
    #[arg(short, long, visible_alias = "listen", default_value = "0.0.0.0:51820")]
    bind: String,

    #[arg(short, long, default_value = "velocity-secret-key")]
    psk: String,

    #[arg(long, visible_alias = "tun-name", default_value = "tun0")]
    tun: String,

    #[arg(long, visible_alias = "tun-ip", default_value = "10.66.0.1/24")]
    tun_cidr: String,

    #[arg(long, default_value_t = 1380)]
    mtu: usize,

    #[arg(long, default_value = "eth0")]
    wan: String,

    #[arg(long, default_value = "10.66.0.0/24")]
    subnet: String,

    #[arg(long, default_value_t = DEFAULT_ORACLE_LIMIT_GB)]
    monthly_limit_gb: u64,

    #[arg(long, default_value_t = false)]
    enforce_quota: bool,

    #[arg(long)]
    usage_file: Option<PathBuf>,

    #[arg(long, default_value = "Oracle Cloud Free")]
    provider_label: String,

    #[arg(long, default_value = "0.0.0.0:8080")]
    status_bind: String,

    #[arg(long)]
    public_host: Option<String>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();
    let bind_addr: SocketAddr = args.bind.parse()?;
    let udp_port = bind_addr.port();

    let quota = Arc::new(QuotaTracker::new(
        args.provider_label.clone(),
        args.monthly_limit_gb,
        args.enforce_quota,
        args.usage_file.clone(),
    ));

    info!("Starting Velocity Multipath Relay Server ({})", args.provider_label);
    info!("Bind: {} | Monthly Egress Limit: {} GiB (enforce={})", bind_addr, args.monthly_limit_gb, args.enforce_quota);
    info!("TUN Interface: {} ({}, MTU {}) | WAN: {}", args.tun, args.tun_cidr, args.mtu, args.wan);

    if let Some(ref host) = args.public_host {
        let encoded_name = args.provider_label.replace(' ', "+");
        info!(
            "Shareable invite URI: velocity://{}@{}:{}?name={}",
            args.psk, host, udp_port, encoded_name
        );
    }

    let _ = NatHelper::setup_nat_with_port(&args.tun, &args.wan, &args.subnet, udp_port);

    #[cfg(target_os = "linux")]
    let server = match velocity_relay::tun::LinuxTun::create(&args.tun, &args.tun_cidr, args.mtu) {
        Ok(tun) => {
            let (reader, writer) = tun.split();
            Arc::new(
                RelayServer::new_with_quota(bind_addr, args.psk.clone(), Arc::clone(&quota))
                    .with_split_tun(Box::new(reader), Arc::new(writer)),
            )
        }
        Err(e) => {
            tracing::warn!("Could not create Linux TUN: {:?}. Running in userspace echo mode.", e);
            Arc::new(RelayServer::new_with_quota(bind_addr, args.psk.clone(), Arc::clone(&quota)))
        }
    };

    #[cfg(not(target_os = "linux"))]
    let server = Arc::new(RelayServer::new_with_quota(
        bind_addr,
        args.psk.clone(),
        Arc::clone(&quota),
    ));

    if !args.status_bind.trim().is_empty() && args.status_bind != "off" {
        if let Ok(status_addr) = args.status_bind.parse::<SocketAddr>() {
            let q = Arc::clone(&quota);
            let s = Arc::clone(&server.sessions);
            let r = Arc::clone(&server.running);
            let n = Arc::clone(&server.shutdown_notify);
            tokio::spawn(async move {
                run_status_http_server(status_addr, q, s, r, n).await;
            });
        }
    }

    let srv_clone = Arc::clone(&server);
    let handle = tokio::spawn(async move {
        if let Err(e) = srv_clone.run().await {
            tracing::error!("Server error: {:?}", e);
        }
    });

    signal::ctrl_c().await?;
    info!("Shutdown signal received, terminating relay server...");

    server.stop();
    NatHelper::teardown_nat_with_port(&args.tun, &args.wan, &args.subnet, udp_port);
    let _ = handle.await;

    info!("Velocity Relay shutdown complete");
    Ok(())
}
