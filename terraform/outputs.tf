# VPC Outputs
output "vpc_id" {
  description = "ID of the VPC"
  value       = module.vpc.vpc_id
}

output "vpc_cidr_block" {
  description = "CIDR block of the VPC"
  value       = module.vpc.vpc_cidr_block
}

# Subnet Outputs
output "public_subnet_ids" {
  description = "IDs of the public subnets"
  value       = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  description = "IDs of the private subnets"
  value       = module.vpc.private_subnet_ids
}

# Load Balancer Outputs
output "alb_dns_name" {
  description = "DNS name of the load balancer"
  value       = module.alb.alb_dns_name
}

output "alb_zone_id" {
  description = "Zone ID of the load balancer"
  value       = module.alb.alb_zone_id
}

output "alb_arn" {
  description = "ARN of the load balancer"
  value       = module.alb.alb_arn
}

# ECS Outputs
output "ecs_cluster_id" {
  description = "ID of the ECS cluster"
  value       = aws_ecs_cluster.main.id
}

output "ecs_cluster_arn" {
  description = "ARN of the ECS cluster"
  value       = aws_ecs_cluster.main.arn
}

# RDS Outputs
output "rds_endpoint" {
  description = "RDS instance endpoint"
  value       = module.rds.db_endpoint
  sensitive   = true
}

output "rds_port" {
  description = "RDS instance port"
  value       = module.rds.db_port
}

# ECR Outputs
output "ecr_repository_urls" {
  description = "URLs of the ECR repositories"
  value = {
    for service, config in var.services : service => aws_ecr_repository.services[service].repository_url
  }
}

# Service URLs
output "service_urls" {
  description = "URLs for accessing the microservices"
  value = {
    for service, config in var.services : service => "http://${module.alb.alb_dns_name}/${service}"
  }
}

output "target_group_arns" {
  description = "Target group ARNs for each microservice"
  value = {
    for service, module_output in module.ecs_service : service => module_output.target_group_arn
  }
}

# Security Group IDs
output "security_group_ids" {
  description = "Security group IDs"
  value = {
    alb = module.alb.security_group_id
    ecs = aws_security_group.ecs.id
    rds = module.rds.security_group_id
  }
}

# Secrets Manager
output "db_secret_arn" {
  description = "ARN of the RDS-managed database credential secret"
  value       = module.rds.db_secret_arn
  sensitive   = true
}

# CI/CD Pipeline
output "codepipeline_name" {
  description = "Name of the CodePipeline"
  value       = aws_codepipeline.grocellery_pipeline.name
}

output "codebuild_project_names" {
  description = "Names of the CodeBuild projects"
  value = {
    app_build = aws_codebuild_project.grocellery_build.name
    terraform = aws_codebuild_project.grocellery_terraform.name
  }
}