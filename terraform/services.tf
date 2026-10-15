# ECR Repositories for each service
resource "aws_ecr_repository" "services" {
  for_each = var.services
  name     = "${local.name_prefix}-${each.key}"

  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = merge(local.common_tags, {
    Name    = "${local.name_prefix}-${each.key}-ecr"
    Service = each.key
    Type    = "container-registry"
  })
}

# ECR Lifecycle Policy
resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = var.services
  repository = aws_ecr_repository.services[each.key].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 30 immutable release images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = 30
        }
        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "Delete untagged images older than 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# ECS Services using the improved module
locals {
  kafka_enabled_services = ["order", "summary"]

  service_runtime_environment = {
    for service_name, service in var.services : service_name => merge(
      contains(local.kafka_enabled_services, service_name) ? {
        KAFKA_BOOTSTRAP_SERVERS                   = local.runtime_config.kafka_bootstrap_servers
        KAFKA_ORDER_CREATED_TOPIC                 = "order.created.v1"
        SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL = local.runtime_config.kafka_security_protocol
      } : {},
      service_name == "order" ? {
        CART_SERVICE_BASE_URL = "http://cart.${local.name_prefix}.local:${var.services["cart"].port}"
      } : {},
      contains(["cart", "order"], service_name) ? {
        PRODUCT_SERVICE_BASE_URL = "http://product.${local.name_prefix}.local:${var.services["product"].port}"
      } : {},
      contains(local.kafka_enabled_services, service_name) && local.runtime_config.kafka_security_protocol == "SASL_SSL" ? {
        SPRING_KAFKA_PROPERTIES_SASL_MECHANISM = try(local.runtime_config.kafka_sasl_mechanism, "")
      } : {}
    )
  }

  service_runtime_secrets = {
    for service_name, service in var.services : service_name => (
      contains(local.kafka_enabled_services, service_name) &&
      local.runtime_config.kafka_security_protocol == "SASL_SSL"
      ? {
        SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG = "${try(local.runtime_config.kafka_sasl_jaas_secret_arn, "")}:jaas_config::"
      }
      : {}
    )
  }
}

resource "terraform_data" "runtime_configuration" {
  input = local.runtime_config

  lifecycle {
    precondition {
      condition = (
        length(trimspace(try(local.runtime_config.cors_allowed_origins, ""))) > 0 &&
        alltrue([
          for origin in split(",", try(local.runtime_config.cors_allowed_origins, "")) :
          can(regex("^https?://[^/*]+$", trimspace(origin)))
        ]) &&
        length(trimspace(try(local.runtime_config.kafka_bootstrap_servers, ""))) > 0 &&
        contains(["SSL", "SASL_SSL"], try(local.runtime_config.kafka_security_protocol, "")) &&
        (
          try(local.runtime_config.kafka_security_protocol, "") == "SASL_SSL"
          ? length(trimspace(try(local.runtime_config.kafka_sasl_mechanism, ""))) > 0 &&
          can(regex("^arn:[^:]+:secretsmanager:", try(local.runtime_config.kafka_sasl_jaas_secret_arn, "")))
          : length(trimspace(try(local.runtime_config.kafka_sasl_mechanism, ""))) == 0 &&
          length(trimspace(try(local.runtime_config.kafka_sasl_jaas_secret_arn, ""))) == 0
        )
      )
      error_message = "Runtime configuration must specify exact CORS origins, Kafka endpoints, and valid SSL or SASL_SSL settings."
    }
  }
}

module "ecs_service" {
  for_each = var.services

  source = "./modules/ecs"

  # Service configuration
  service_name   = each.key
  image_uri      = "${aws_ecr_repository.services[each.key].repository_url}:${var.image_tag}"
  container_port = each.value.port

  # Resource allocation
  task_cpu          = each.value.cpu
  task_memory       = each.value.memory
  desired_count     = each.value.desired_count
  health_check_path = each.value.health_check_path

  # Infrastructure references
  aws_region                     = var.aws_region
  environment                    = var.environment
  project_name                   = var.project_name
  ecs_cluster_id                 = aws_ecs_cluster.main.id
  vpc_id                         = module.vpc.vpc_id
  private_subnet_ids             = module.vpc.private_subnet_ids
  private_subnet_cidrs           = module.vpc.private_subnet_cidrs
  service_discovery_namespace_id = aws_service_discovery_private_dns_namespace.services.id
  cors_allowed_origins           = local.runtime_config.cors_allowed_origins
  runtime_environment            = local.service_runtime_environment[each.key]
  runtime_secrets                = local.service_runtime_secrets[each.key]

  # Load balancer configuration
  alb_security_group_id      = module.alb.security_group_id
  alb_listener_arn           = module.alb.listener_arn
  alb_listener_rule_priority = (index(keys(var.services), each.key) + 1) * 10

  # Database configuration
  db_endpoint                = module.rds.db_endpoint
  db_port                    = module.rds.db_port
  db_name                    = module.rds.db_name
  db_username                = var.db_username
  db_secret_arn              = module.rds.db_secret_arn
  jwt_secret_arn             = aws_secretsmanager_secret.oidc_config.arn
  secrets_kms_key_arn        = aws_kms_key.secrets.arn
  kafka_sasl_jaas_secret_arn = try(local.runtime_config.kafka_sasl_jaas_secret_arn, "")

  # Monitoring
  enable_monitoring = var.enable_monitoring

  # Tags
  common_tags = local.common_tags

  depends_on = [
    module.rds,
    aws_secretsmanager_secret_version.oidc_config_version,
    terraform_data.runtime_configuration
  ]
}
