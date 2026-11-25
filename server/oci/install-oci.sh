#!/usr/bin/env bash
set -euo pipefail

# Oracle Cloud Always Free (VM.Standard.A1.Flex ARM64 / Ubuntu 22.04-24.04) Native Setup
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/<repo>/main/server/oci/install-oci.sh | sudo bash
# Or locally from repo root:
#   sudo PSK="$(openssl rand -hex 16)" MONTHLY_LIMIT_GB=10240 bash server/oci/install-oci.sh

if [ "${EUID}" -ne 0 ]; then
  echo "Please run as root (sudo)."
  exit 1
fi

REPO_URL="${REPO_URL:-https://github.com/velocity-multipath/Velocity.git}"
SRC_DIR="${SRC_DIR:-/opt/velocity-src}"
BIN_PATH="/usr/local/bin/velocity-relay"
STATE_DIR="/var/lib/velocity-relay"
BIND_PORT="${BIND_PORT:-51820}"
STATUS_PORT="${STATUS_PORT:-8080}"
PSK="${PSK:-$(openssl rand -hex 16)}"
MONTHLY_LIMIT_GB="${MONTHLY_LIMIT_GB:-10240}"
ENFORCE_QUOTA="${ENFORCE_QUOTA:-true}"
PROVIDER_LABEL="${PROVIDER_LABEL:-Oracle Cloud Free}"

WAN_IFACE="${WAN_IFACE:-$(ip -4 route list default | awk '{print $5; exit}')}"
WAN_IFACE="${WAN_IFACE:-ens3}"
PUBLIC_IP="${PUBLIC_IP:-$(curl -fsSL --max-time 3 https://checkip.amazonaws.com 2>/dev/null || curl -fsSL --max-time 3 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')}"

echo "=== Velocity Oracle Cloud Relay Installer ==="
echo "WAN Interface : $WAN_IFACE"
echo "Public IP     : $PUBLIC_IP"
echo "UDP Port      : $BIND_PORT"
echo "Status Port   : $STATUS_PORT"
echo "Monthly Limit : $MONTHLY_LIMIT_GB GiB (enforce=$ENFORCE_QUOTA)"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
  build-essential curl git pkg-config iproute2 iptables iptables-persistent netfilter-persistent ca-certificates

# Install Rust if missing
if ! command -v cargo >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
  export PATH="$HOME/.cargo/bin:$PATH"
fi
export PATH="/root/.cargo/bin:$PATH"

# Use current checkout if invoked from inside repo, otherwise clone
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../velocity-relay/Cargo.toml" ]; then
  BUILD_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
else
  rm -rf "$SRC_DIR"
  git clone --depth 1 "$REPO_URL" "$SRC_DIR"
  BUILD_ROOT="$SRC_DIR"
fi

echo "Building velocity-relay optimized for host CPU (${BUILD_ROOT})..."
RUSTFLAGS="-C target-cpu=native -C lto=thin" \
  cargo build --release --manifest-path "$BUILD_ROOT/server/Cargo.toml" -p velocity-relay
install -m 0755 "$BUILD_ROOT/server/target/release/velocity-relay" "$BIN_PATH"

# Apply Linux / Oracle Cloud kernel tuning
mkdir -p "$STATE_DIR"
if [ -f "$BUILD_ROOT/server/oci/99-velocity-sysctl.conf" ]; then
  cp "$BUILD_ROOT/server/oci/99-velocity-sysctl.conf" /etc/sysctl.d/99-velocity-sysctl.conf
  sysctl --system >/dev/null
fi

# Persist OCI iptables firewall rules ahead of Oracle's default REJECT rules
iptables -C INPUT -p udp --dport "$BIND_PORT" -j ACCEPT 2>/dev/null || \
  iptables -I INPUT 1 -p udp --dport "$BIND_PORT" -j ACCEPT
iptables -C INPUT -p tcp --dport "$STATUS_PORT" -j ACCEPT 2>/dev/null || \
  iptables -I INPUT 2 -p tcp --dport "$STATUS_PORT" -j ACCEPT
netfilter-persistent save >/dev/null 2>&1 || true

# Persist relay configuration & systemd service
cat > /etc/default/velocity-relay <<EOF
PSK=${PSK}
BIND=0.0.0.0:${BIND_PORT}
STATUS_BIND=0.0.0.0:${STATUS_PORT}
TUN=tun0
TUN_CIDR=10.66.0.1/24
MTU=1380
WAN=${WAN_IFACE}
SUBNET=10.66.0.0/24
MONTHLY_LIMIT_GB=${MONTHLY_LIMIT_GB}
PROVIDER_LABEL=${PROVIDER_LABEL}
PUBLIC_HOST=${PUBLIC_IP}
EOF

ENFORCE_FLAG=""
if [ "$ENFORCE_QUOTA" = "true" ]; then
  ENFORCE_FLAG="--enforce-quota"
fi

cat > /etc/systemd/system/velocity-relay.service <<EOF
[Unit]
Description=Velocity Multipath Bonding Relay Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/default/velocity-relay
ExecStart=${BIN_PATH} \\
  --bind \${BIND} \\
  --status-bind \${STATUS_BIND} \\
  --psk \${PSK} \\
  --tun \${TUN} \\
  --tun-cidr \${TUN_CIDR} \\
  --mtu \${MTU} \\
  --wan \${WAN} \\
  --subnet \${SUBNET} \\
  --monthly-limit-gb \${MONTHLY_LIMIT_GB} \\
  --provider-label "\${PROVIDER_LABEL}" \\
  --usage-file ${STATE_DIR}/usage.json \\
  --public-host \${PUBLIC_HOST} \\
  ${ENFORCE_FLAG}
Restart=always
RestartSec=2
LimitNOFILE=1048576
Nice=-10
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now velocity-relay

INVITE_URI="velocity://${PSK}@${PUBLIC_IP}:${BIND_PORT}?name=$(printf '%s' "$PROVIDER_LABEL" | sed 's/ /+/g')"
echo ""
echo "================================================================="
echo "Velocity Relay is LIVE on Oracle Cloud!"
echo "Invite URI (paste in Velocity app -> Relays -> Add -> Paste Link):"
echo "  ${INVITE_URI}"
echo "Live Monthly Quota JSON:"
echo "  http://${PUBLIC_IP}:${STATUS_PORT}/quota"
echo "================================================================="
