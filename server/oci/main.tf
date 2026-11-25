terraform {
  required_version = ">= 1.3.0"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 5.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.5.0"
    }
  }
}

provider "oci" {
  region = var.region
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ubuntu_arm" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "random_id" "relay_psk" {
  byte_length = 16
}

locals {
  effective_psk = var.relay_psk != "" ? var.relay_psk : random_id.relay_psk.hex

  cloud_init = base64encode(<<-CLOUDINIT
    #!/usr/bin/env bash
    set -euxo pipefail
    export PSK="${local.effective_psk}"
    export BIND_PORT="${var.relay_port}"
    export STATUS_PORT="${var.status_port}"
    export MONTHLY_LIMIT_GB="${var.monthly_limit_gb}"
    export ENFORCE_QUOTA="${var.enforce_quota}"
    export PROVIDER_LABEL="${var.provider_label}"
    export REPO_URL="${var.repo_url}"
    curl -fsSL "${var.install_script_raw_url}" | bash
    CLOUDINIT
  )
}

resource "oci_core_vcn" "velocity_vcn" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.20.0.0/16"]
  display_name   = "velocity-relay-vcn"
  dns_label      = "velocityvcn"
}

resource "oci_core_internet_gateway" "velocity_igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.velocity_vcn.id
  display_name   = "velocity-relay-igw"
  enabled        = true
}

resource "oci_core_route_table" "velocity_rt" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.velocity_vcn.id
  display_name   = "velocity-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.velocity_igw.id
  }
}

resource "oci_core_security_list" "velocity_sl" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.velocity_vcn.id
  display_name   = "velocity-relay-seclist"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  # Velocity encrypted VL01 UDP tunnel
  ingress_security_rules {
    protocol = "17" # UDP
    source   = "0.0.0.0/0"
    udp_options {
      min = var.relay_port
      max = var.relay_port
    }
  }

  # Optional public HTTP /quota & /health endpoint
  ingress_security_rules {
    protocol = "6" # TCP
    source   = "0.0.0.0/0"
    tcp_options {
      min = var.status_port
      max = var.status_port
    }
  }

  # SSH administration
  ingress_security_rules {
    protocol = "6" # TCP
    source   = "0.0.0.0/0"
    tcp_options {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_subnet" "velocity_subnet" {
  compartment_id    = var.compartment_ocid
  vcn_id            = oci_core_vcn.velocity_vcn.id
  cidr_block        = "10.20.1.0/24"
  display_name      = "velocity-public-subnet"
  dns_label         = "velocitysub"
  route_table_id    = oci_core_route_table.velocity_rt.id
  security_list_ids = [oci_core_security_list.velocity_sl.id]
}

resource "oci_core_instance" "velocity_relay" {
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[var.ad_index].name
  compartment_id      = var.compartment_ocid
  display_name        = "velocity-relay-arm64"
  shape               = var.instance_shape

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.velocity_subnet.id
    assign_public_ip = true
    display_name     = "velocity-vnic"
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_arm.images[0].id
    boot_volume_size_in_gbs = 50
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = local.cloud_init
  }
}
