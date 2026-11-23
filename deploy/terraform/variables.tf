variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "ap-southeast-1"
}

variable "environment" {
  description = "Environment name, used as a prefix (dev, staging, prod)."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of dev, staging, prod."
  }
}

variable "services" {
  description = "Services that get a container repository and database credentials."
  type        = set(string)
  default     = ["campaign-service", "delivery-worker"]
}

variable "images_to_keep" {
  description = "Number of tagged images kept per repository; older ones expire."
  type        = number
  default     = 20
}
