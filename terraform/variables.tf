variable "aws_region" {
  description = "AWS region for resource deployment"
  type        = string
  default     = "us-east-1"
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]$", var.aws_region))
    error_message = "AWS region must be in format like 'us-east-1'."
  }
}

variable "project_name" {
  description = "Name of the project (used for resource naming)"
  type        = string
  default     = "grocery-ecommerce-platform"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*[a-z0-9]$", var.project_name))
    error_message = "Project name must start with a letter, contain only lowercase letters, numbers, and hyphens."
  }
}

variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "dev"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, staging, prod."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for VPC"
  type        = string
  default     = "10.0.0.0/16"
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "VPC CIDR must be a valid IPv4 CIDR block."
  }
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
  validation {
    condition     = length(var.public_subnet_cidrs) >= 2
    error_message = "At least 2 public subnets are required for high availability."
  }
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets"
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.20.0/24"]
  validation {
    condition     = length(var.private_subnet_cidrs) >= 2
    error_message = "At least 2 private subnets are required for high availability."
  }
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t3.micro"
  validation {
    condition     = can(regex("^db\\.[a-z0-9]+\\.[a-z0-9]+$", var.db_instance_class))
    error_message = "DB instance class must be in format like 'db.t3.micro'."
  }
}

variable "db_username" {
  description = "Database master username"
  type        = string
  default     = "grocellery"
  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]*$", var.db_username))
    error_message = "Database username must start with a letter and contain only alphanumeric characters and underscores."
  }
}

variable "jwt_issuer_uri" {
  description = "OIDC issuer URL used by the resource servers for JWKS discovery. The prod profile fails startup without it."
  type        = string
  default     = "https://auth.example.com/realms/grocery"
  validation {
    condition     = can(regex("^https://", var.jwt_issuer_uri))
    error_message = "JWT issuer URI must be an HTTPS URL."
  }
}

variable "jwt_audience" {
  description = "Required JWT audience claim, shared by all microservices."
  type        = string
  default     = "grocery-api"
}

variable "services" {
  description = "Configuration for microservices"
  type = map(object({
    port              = number
    cpu               = number
    memory            = number
    desired_count     = number
    health_check_path = string
  }))
  default = {
    cart = {
      port              = 8081
      cpu               = 256
      memory            = 512
      desired_count     = 1
      health_check_path = "/actuator/health"
    }
    order = {
      port              = 8082
      cpu               = 256
      memory            = 512
      desired_count     = 1
      health_check_path = "/actuator/health"
    }
    product = {
      port              = 8083
      cpu               = 256
      memory            = 512
      desired_count     = 1
      health_check_path = "/actuator/health"
    }
    summary = {
      port              = 8084
      cpu               = 256
      memory            = 512
      desired_count     = 1
      health_check_path = "/actuator/health"
    }
  }

  validation {
    condition     = alltrue([for name in ["cart", "order", "product", "summary"] : contains(keys(var.services), name)])
    error_message = "The cart, order, product, and summary service configurations are required."
  }
}

variable "image_tag" {
  description = "Immutable container image tag deployed by ECS, usually sha-<commit> from CodeBuild"
  type        = string
  default     = "latest"
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+$", var.image_tag))
    error_message = "Image tag can contain only letters, numbers, periods, underscores, and hyphens."
  }
}

variable "enable_monitoring" {
  description = "Enable CloudWatch monitoring and logging"
  type        = bool
  default     = true
}

variable "enable_backup" {
  description = "Enable automated backups for RDS"
  type        = bool
  default     = true
}

variable "backup_retention_period" {
  description = "Number of days to retain automated backups"
  type        = number
  default     = 7
  validation {
    condition     = var.backup_retention_period >= 0 && var.backup_retention_period <= 35
    error_message = "Backup retention period must be between 0 and 35 days."
  }
}

variable "common_tags" {
  description = "Common tags to apply to all resources"
  type        = map(string)
  default = {
    Project   = "grocery-ecommerce-platform"
    ManagedBy = "terraform"
    Owner     = "devops-team"
  }
}
