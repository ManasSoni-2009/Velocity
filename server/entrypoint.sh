#!/bin/sh
set -e

echo "=== Velocity Multipath Relay Server ==="
echo "Configuring network environment..."

sysctl -w net.ipv4.ip_forward=1 || true
sysctl -w net.ipv4.conf.all.rp_filter=0 || true
sysctl -w net.ipv4.conf.default.rp_filter=0 || true

if ip link show "$WAN" > /dev/null 2>&1; then
    echo "Configuring iptables NAT + MSS clamping for subnet $SUBNET via $WAN"
    iptables -t nat -A POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE || true
    iptables -I FORWARD 1 -i "$TUN" -o "$WAN" -j ACCEPT || true
    iptables -I FORWARD 1 -i "$WAN" -o "$TUN" -m state --state RELATED,ESTABLISHED -j ACCEPT || true
    iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mtu-to-pmtu || true
fi

mkdir -p "$(dirname "${USAGE_FILE:-/var/lib/velocity-relay/usage.json}")"

EFFECTIVE_PSK="${PSK:-${VELOCITY_PSK:-velocity-secret-key}}"

echo "Starting velocity-relay binary..."
exec /app/velocity-relay \
    --bind "${BIND:-0.0.0.0:51820}" \
    --status-bind "${STATUS_BIND:-0.0.0.0:8080}" \
    --psk "$EFFECTIVE_PSK" \
    --tun "${TUN:-tun0}" \
    --tun-cidr "${TUN_CIDR:-10.66.0.1/24}" \
    --mtu "${MTU:-1380}" \
    --wan "${WAN:-eth0}" \
    --subnet "${SUBNET:-10.66.0.0/24}" \
    --monthly-limit-gb "${MONTHLY_LIMIT_GB:-10240}" \
    --provider-label "${PROVIDER_LABEL:-Oracle Cloud Free}" \
    --usage-file "${USAGE_FILE:-/var/lib/velocity-relay/usage.json}"
