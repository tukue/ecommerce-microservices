provider "aws" {
  region = var.aws_region

  default_tags {
    tags = merge(var.common_tags, {
      Environment = var.environment
      Region      = var.aws_region
    })
  }
}

# Local values for consistent naming and tagging
locals {
  name_prefix = "${var.project_name}-${var.environment}"

  common_tags = merge(var.common_tags, {
    Environment = var.environment
    Region      = var.aws_region
    Terraform   = "true"
  })
  runtime_config = jsondecode(data.aws_ssm_parameter.runtime_configuration.value)
}

# Data sources
data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

data "aws_ssm_parameter" "runtime_configuration" {
  name = "/${var.project_name}/${var.environment}/runtime-configuration"
}

# VPC Module
module "vpc" {
  source = "./modules/vpc"

  name_prefix          = local.name_prefix
  vpc_cidr             = var.vpc_cidr
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  availability_zones   = data.aws_availability_zones.available.names
  common_tags          = local.common_tags
}

# ALB Module
module "alb" {
  source = "./modules/alb"

  name_prefix       = local.name_prefix
  environment       = var.environment
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  common_tags       = local.common_tags
}

resource "aws_service_discovery_private_dns_namespace" "services" {
  name        = "${local.name_prefix}.local"
  description = "Private DNS namespace for ECS microservices"
  vpc         = module.vpc.vpc_id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-dns-namespace"
    Type = "service-discovery"
  })
}

# RDS Module
module "rds" {
  source = "./modules/rds"

  name_prefix             = local.name_prefix
  project_name            = var.project_name
  environment             = var.environment
  vpc_id                  = module.vpc.vpc_id
  private_subnet_ids      = module.vpc.private_subnet_ids
  allowed_security_groups = [aws_security_group.ecs.id]
  db_instance_class       = var.db_instance_class
  db_username             = var.db_username
  backup_retention_period = var.backup_retention_period
  common_tags             = local.common_tags
}

# ECS Security Group
resource "aws_security_group" "ecs" {
  name_prefix = "${local.name_prefix}-ecs-"
  vpc_id      = module.vpc.vpc_id

  ingress {
    from_port       = 8080
    to_port         = 8090
    protocol        = "tcp"
    security_groups = [module.alb.security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-ecs-sg"
    Type = "security-group"
  })
}