variable "tenancy_ocid" {
  type        = string
  description = "OCI Tenancy OCID"
}

variable "compartment_ocid" {
  type        = string
  description = "OCI Compartment OCID (can equal tenancy_ocid for root compartment)"
}

variable "region" {
  type        = string
  description = "OCI region identifier (e.g. ap-mumbai-1, us-ashburn-1, eu-frankfurt-1)"
}

variable "ad_index" {
  type        = number
  default     = 0
  description = "Availability domain index (0, 1, or 2 if Always Free capacity is out in AD 0)"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key for instance administration"
}

variable "instance_shape" {
  type        = string
  default     = "VM.Standard.A1.Flex"
  description = "Always Free Ampere ARM64 shape (4 Gbps VNIC when configured with 4 OCPUs)"
}

variable "ocpus" {
  type        = number
  default     = 4
  description = "Always Free allows up to 4 Ampere OCPUs total (4 Gbps network bandwidth)"
}

variable "memory_in_gbs" {
  type        = number
  default     = 24
  description = "Always Free allows up to 24 GB RAM total"
}

variable "relay_port" {
  type    = number
  default = 51820
}

variable "status_port" {
  type    = number
  default = 8080
}

variable "relay_psk" {
  type      = string
  default   = ""
  sensitive = true
  description = "Optional custom pre-shared key; auto-generated if left empty"
}

variable "monthly_limit_gb" {
  type        = number
  default     = 10240
  description = "Monthly outbound transfer budget in GiB (Oracle Always Free = 10 TiB = 10240 GiB)"
}

variable "enforce_quota" {
  type        = bool
  default     = true
  description = "Pause forwarding automatically if monthly limit is reached to guarantee $0 overage"
}

variable "provider_label" {
  type    = string
  default = "Oracle Cloud Free"
}

variable "repo_url" {
  type    = string
  default = "https://github.com/velocity-multipath/Velocity.git"
}

variable "install_script_raw_url" {
  type    = string
  default = "https://raw.githubusercontent.com/velocity-multipath/Velocity/main/server/oci/install-oci.sh"
}
