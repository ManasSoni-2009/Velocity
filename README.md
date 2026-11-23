# ⚡ Velocity — High-Performance Multipath Bonding & Hot-Failover Engine

Velocity is an open-source, clean-room multipath network bonding engine and modern cross-platform client (Android & Desktop) designed for maximum combined throughput, ultra-low latency, and seamless roaming across heterogeneous network interfaces (Wi-Fi + Cellular).

Built with a high-performance **Rust** packet processing core and a stunning **Kotlin Multiplatform (Compose Multiplatform)** interface following Apple's **Liquid Glass** Human Interface Guidelines.

---

## 🚀 Key Features

### 1. Multipath Protocol (VL01)
* **Custom Binary Framing Header**: 40-byte compact header featuring magic byte `VL`, flags, path ID, 64-bit session ID for seamless IP/NAT roaming, 64-bit packet sequence numbers, selective ACK bitmap, and microsecond TX timestamp.
* **ChaCha20-Poly1305 AEAD**: End-to-end authenticated encryption with low CPU overhead and high packet throughput on mobile ARM and desktop chips.
* **Zero-Allocation Packet Pool**: Pre-allocated 1600-byte frame ring buffer avoiding GC or allocator jitter in the fast forwarding path.

### 2. Intelligent Scheduling Engines
* **Speed Mode (Mode 0)**: Arrival-time-compensated BDP (Bandwidth-Delay Product) weighted deficit round-robin. Actively predicts packet transit times over disparate interfaces (e.g. 15ms Wi-Fi vs 45ms 5G) and spaces transmissions so packets arrive at the receiver in exact sequence, virtually eliminating head-of-line blocking.
* **Low-Latency Mode (Mode 1)**: Small packets (<256 bytes, TCP SYN/ACK, DNS, gaming UDP) are routed across the lowest-RTT path, supplemented by adaptive XOR Forward Error Correction (FEC) or selective multi-interface duplication when jitter or packet loss spikes (>1%).
* **Hot-Failover Mode (Mode 2)**: Primary Wi-Fi link with low-overhead 250ms background Cellular heartbeat probes, achieving sub-100ms failover with zero socket drop when walking out of range.
* **Adaptive Reorder Ring Buffer**: Dynamically clamps timeout window between `[15ms, 120ms]` based on `1.5 * |RTT_wifi - RTT_cellular| + 10ms`.

### 3. Apple Liquid Glass UI Design
* **True Liquid Glass HIG**: Authentic floating functional layer over an obsidian/oceanic canvas.
* **GPU Shader Shading**: Built with Android 13+ AGSL (`RuntimeShader` / `RenderEffect`) and Desktop Skiko SkSL with real-time backdrop blur, edge refraction, and directional 1dp specular rim highlight.
* **Fluid Spring Dynamics**: `Spring.DampingRatioLowBouncy` physics for interactive controls, morphing hero orb, and concentric corner geometry (`concentricRadius`).
* **Accessibility First**: First-class support for system "Reduce Transparency" and "High Contrast" settings with crisp solid-tint fallbacks.

### 4. Cross-Platform Architecture
* **Android**: `VpnService` tunnel intercepting system traffic, `ConnectivityManager` dual-link socket binding (`TRANSPORT_WIFI` & `TRANSPORT_CELLULAR`), per-app split tunneling, and foreground telemetry notification.
* **Desktop (Windows / macOS / Linux)**: Compose Multiplatform desktop application with full telemetry monitor, speedtest client, and relay management.
* **Self-Hosted Relay Server**: Lightweight, asynchronous `tokio` Linux relay server with TUN interface integration, multi-session roaming table, and built-in speedtest echo responder.

---

## 🏗️ Architecture Overview

```
                      +---------------------------------------+
                      |       Velocity Client (Android)       |
                      |  +---------------------------------+  |
                      |  |     Compose Multiplatform UI    |  |
                      |  |     (Apple Liquid Glass HIG)    |  |
                      |  +----------------+----------------+  |
                      |                   | JNI               |
                      |  +----------------v----------------+  |
                      |  |    Rust Core Engine (cdylib)    |  |
                      |  |  [Scheduler / BDP / FEC / AEAD] |  |
                      |  +--------+---------------+--------+  |
                      +-----------|---------------|-----------+
                        Wi-Fi Path|               |Cellular (5G/LTE)
                                  |               |
                                  v               v
                      +---------------------------------------+
                      |         Internet / WAN Mesh           |
                      +-------------------+-------------------+
                                          |
                      +-------------------v-------------------+
                      |      Velocity Relay Server (Linux)    |
                      |  +---------------------------------+  |
                      |  |   Rust tokio UDP Async Core     |  |
                      |  |   Session Table & Roaming NAT   |  |
                      |  |   Linux TUN (/dev/net/tun)      |  |
                      |  +----------------+----------------+  |
                      +-------------------|-------------------+
                                          |
                                    Internet (WAN)
```

---

## 🛠️ Build & Development Setup

### Prerequisites
* **Rust**: `1.80+` with toolchains:
  * Host: `x86_64-pc-windows-msvc` (or `x86_64-unknown-linux-gnu`)
  * Cross-compile targets: `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `aarch64-unknown-linux-gnu`
  * Cargo tools: `cargo-ndk`
* **Java / JDK**: OpenJDK 17 (`JAVA_HOME`)
* **Android SDK & NDK**:
  * SDK Platforms: `android-35`
  * Build Tools: `35.0.0`
  * NDK: `27.2.12479018`
  * Environment variables: `ANDROID_HOME`, `ANDROID_NDK_HOME`

### Building the Rust Native Core & Relay
```bash
# Workspace tests (unit tests, scheduler benchmark, AEAD verification)
cargo test --workspace --all-targets

# Build Linux Relay binary
cargo build --release --bin velocity-relay

# Build Android JNI native libraries for all ABI architectures
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o composeApp/src/androidMain/jniLibs build --release -p velocity-jni
```

### Building the Android & Desktop Client
```bash
# Run unit tests (JVM & Android)
./gradlew :composeApp:testDebugUnitTest :composeApp:jvmTest

# Build Android Debug & Release APKs
./gradlew :composeApp:assembleDebug :composeApp:assembleRelease

# Package Desktop distribution (UberJar / MSI / DMG / Deb)
./gradlew :composeApp:packageUberJarForCurrentOS
```

---

## 🌐 Self-Hosted Relay Deployment

Run your own dedicated Velocity relay on any Linux VPS (Ubuntu, Debian, Fedora, Arch) using Docker:

### Quickstart with Docker Compose
Create `docker-compose.yml`:
```yaml
version: '3.8'

services:
  velocity-relay:
    image: ghcr.io/manassoni-2009/velocity-relay:latest
    container_name: velocity-relay
    restart: unless-stopped
    cap_add:
      - NET_ADMIN
    devices:
      - /dev/net/tun:/dev/net/tun
    ports:
      - "51820:51820/udp"
    environment:
      - VELOCITY_LISTEN_ADDR=0.0.0.0:51820
      - VELOCITY_TUN_IP=10.66.0.1/24
      - VELOCITY_PSK=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
      - VELOCITY_LOG=info
```

Run:
```bash
docker compose up -d
```

### Direct Linux Binary Deployment
```bash
# Enable IP forwarding on the host
sudo sysctl -w net.ipv4.ip_forward=1

# Configure NAT Masquerade on your WAN interface (e.g. eth0)
sudo iptables -t nat -A POSTROUTING -s 10.66.0.0/24 -o eth0 -j MASQUERADE

# Run the relay binary
sudo ./velocity-relay \
  --listen 0.0.0.0:51820 \
  --tun-cidr 10.66.0.1/24 \
  --psk 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

### Client URI Configuration
Import your server into the Velocity app via QR code or `velocity://` URI:
```
velocity://relay.example.com:51820?psk=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef&name=Frankfurt-Relay&mtu=1380
```

---

## 📄 License
Licensed under the [MIT License](LICENSE). Copyright (c) 2026 Velocity Contributors.
