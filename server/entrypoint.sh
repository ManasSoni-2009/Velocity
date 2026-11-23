#!/bin/sh
set -e

echo "=== Velocity Multipath Relay Server ==="
echo "Configuring network environment..."

# Enable IP forwarding
sysctl -w net.ipv4.ip_forward=1 || true

# Setup NAT iptables rules if WAN interface exists
if ip link show "$WAN" > /dev/null 2>&1; then
    echo "Configuring iptables NAT for subnet $SUBNET via $WAN"
    iptables -t nat -A POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE || true
    iptables -A FORWARD -i "$TUN" -o "$WAN" -j ACCEPT || true
    iptables -A FORWARD -i "$WAN" -o "$TUN" -m state --state RELATED,ESTABLISHED -j ACCEPT || true
fi

echo "Starting velocity-relay binary..."
exec /app/velocity-relay \
    --bind "$BIND" \
    --psk "$PSK" \
    --tun "$TUN" \
    --wan "$WAN" \
    --subnet "$SUBNET"
