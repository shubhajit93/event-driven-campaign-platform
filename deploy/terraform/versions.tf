terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Use a remote backend for real environments, for example:
  # backend "s3" {
  #   bucket         = "my-terraform-state"
  #   key            = "campaign-platform/terraform.tfstate"
  #   region         = "ap-southeast-1"
  #   dynamodb_table = "terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "event-driven-campaign-platform"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
