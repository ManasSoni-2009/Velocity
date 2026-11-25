# Oracle Cloud Always Free Setup Guide

Velocity is tuned for Oracle Cloud Infrastructure (**`VM.Standard.A1.Flex`** Ampere ARM64 — **4 OCPUs, 24 GB RAM, 4 Gbps VNIC, and 10 TB/month Always Free outbound transfer**).

## Option A — Automated Terraform / OCI Cloud Shell Deploy

1. Open **Oracle Cloud Console -> Cloud Shell** (top-right terminal icon).
2. Clone your repo or copy `server/oci/`, then run:
   ```bash
   cd server/oci
   terraform init
   terraform apply \
     -var="tenancy_ocid=<your-tenancy-ocid>" \
     -var="compartment_ocid=<your-compartment-ocid>" \
     -var="region=<your-home-region>" \
     -var="ssh_public_key=$(cat ~/.ssh/id_rsa.pub)"
   ```
3. Print your app invite link:
   ```bash
   terraform output -raw velocity_invite_uri
   ```
   Paste that `velocity://...` link into the Velocity phone app (**Relays -> Add -> Paste Link**).

> **Tip:** If your OCI home region returns `Out of host capacity` for `VM.Standard.A1.Flex` in `ad_index=0`, retry with `-var="ad_index=1"` or `-var="ad_index=2"`.

---

## Option B — Install on an Existing Ubuntu 22.04 / 24.04 OCI VM

1. In your OCI **Subnet Security List**, allow:
   - Ingress **UDP `51820`** from `0.0.0.0/0` (VL01 tunnel + encrypted probe & quota)
   - Ingress **TCP `8080`** from `0.0.0.0/0` (optional public `/quota` status JSON)
2. SSH into the VM and run:
   ```bash
   sudo MONTHLY_LIMIT_GB=10240 ENFORCE_QUOTA=true bash server/oci/install-oci.sh
   ```
3. `install-oci.sh` automatically:
   - Compiles `velocity-relay` natively for Ampere ARM64 (`-C target-cpu=native -C lto=thin`)
   - Applies [99-velocity-sysctl.conf](./99-velocity-sysctl.conf) (25 MiB UDP buffers, BBR + FQ, conntrack 262k, IP forwarding)
   - Inserts `iptables` rules **ahead of Oracle Cloud's default `REJECT` rules** + TCP MSS clamping
   - Enables the `velocity-relay.service` daemon with persistent monthly quota tracking in `/var/lib/velocity-relay/usage.json`
