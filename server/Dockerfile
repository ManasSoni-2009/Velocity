# Multi-stage Dockerfile for Velocity Multipath Relay Server
FROM rust:1.80-bullseye AS builder

WORKDIR /usr/src/velocity

# Copy workspace dependencies
COPY native/velocity-proto native/velocity-proto
COPY server/velocity-relay server/velocity-relay

# Build release binary
WORKDIR /usr/src/velocity/server/velocity-relay
RUN cargo build --release

# Final runtime image
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    iptables \
    iproute2 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /usr/src/velocity/server/velocity-relay/target/release/velocity-relay /app/velocity-relay
COPY server/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh /app/velocity-relay

EXPOSE 51820/udp

ENV BIND="0.0.0.0:51820"
ENV PSK="velocity-secret-key"
ENV TUN="tun0"
ENV WAN="eth0"
ENV SUBNET="10.66.0.0/24"

ENTRYPOINT ["/app/entrypoint.sh"]
