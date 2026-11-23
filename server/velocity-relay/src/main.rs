use clap::Parser;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::signal;
use tracing::info;
use velocity_relay::nat::NatHelper;
use velocity_relay::server::RelayServer;

#[derive(Parser, Debug)]
#[command(name = "velocity-relay")]
#[command(about = "Velocity multipath bonding relay server for Linux", long_about = None)]
struct Args {
    #[arg(short, long, default_value = "0.0.0.0:51820")]
    bind: String,

    #[arg(short, long, default_value = "velocity-secret-key")]
    psk: String,

    #[arg(long, default_value = "tun0")]
    tun: String,

    #[arg(long, default_value = "eth0")]
    wan: String,

    #[arg(long, default_value = "10.66.0.0/24")]
    subnet: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    let args = Args::parse();
    let bind_addr: SocketAddr = args.bind.parse()?;

    info!("Starting Velocity Multipath Relay Server");
    info!("Bind: {}", bind_addr);
    info!("TUN Interface: {}", args.tun);
    info!("WAN Interface: {}", args.wan);

    let _ = NatHelper::setup_nat(&args.tun, &args.wan, &args.subnet);

    let server = Arc::new(RelayServer::new(bind_addr, args.psk));
    let srv_clone = Arc::clone(&server);

    let handle = tokio::spawn(async move {
        if let Err(e) = srv_clone.run().await {
            tracing::error!("Server error: {:?}", e);
        }
    });

    signal::ctrl_c().await?;
    info!("Shutdown signal received, terminating relay server...");

    server.stop();
    NatHelper::teardown_nat(&args.tun, &args.wan, &args.subnet);
    let _ = handle.await;

    info!("Velocity Relay shutdown complete");
    Ok(())
}
