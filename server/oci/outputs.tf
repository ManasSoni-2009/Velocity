output "relay_public_ip" {
  value       = oci_core_instance.velocity_relay.public_ip
  description = "Public IPv4 address of the Oracle Cloud Velocity Relay"
}

output "velocity_invite_uri" {
  value       = "velocity://${local.effective_psk}@${oci_core_instance.velocity_relay.public_ip}:${var.relay_port}?name=${replace(var.provider_label, " ", "+")}"
  sensitive   = true
  description = "Paste or open this link in the Velocity Android app"
}

output "status_quota_url" {
  value       = "http://${oci_core_instance.velocity_relay.public_ip}:${var.status_port}/quota"
  description = "Public JSON endpoint showing monthly egress usage vs 10 TB limit"
}
