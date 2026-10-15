locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_ecs_task_definition" "service" {
  family                   = "${local.name_prefix}-${var.service_name}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = var.image_uri
      cpu       = var.task_cpu
      memory    = var.task_memory
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = concat([
        {
          name  = "SPRING_PROFILES_ACTIVE"
          value = "prod"
        },
        {
          name  = "SERVER_PORT"
          value = tostring(var.container_port)
        },
        {
          name  = "SPRING_DATASOURCE_URL"
          value = "jdbc:postgresql://${var.db_endpoint}:${var.db_port}/${var.db_name}"
        },
        {
          name  = "SPRING_DATASOURCE_USERNAME"
          value = var.db_username
        },
        {
          name  = "CORS_ALLOWED_ORIGINS"
          value = var.cors_allowed_origins
        }
        ], [
        for name, value in var.runtime_environment : {
          name  = name
          value = value
        }
      ])

      secrets = concat([
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${var.db_secret_arn}:password::"
        },
        {
          name      = "JWT_ISSUER_URI"
          valueFrom = "${var.jwt_secret_arn}:issuer_uri::"
        },
        {
          name      = "JWT_AUDIENCE"
          valueFrom = "${var.jwt_secret_arn}:audience::"
        }
        ], [
        for name, value_from in var.runtime_secrets : {
          name      = name
          valueFrom = value_from
        }
      ])

      healthCheck = {
        command = [
          "CMD-SHELL",
          "curl -f http://localhost:${var.container_port}${var.health_check_path} || exit 1"
        ]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.service.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      # Security settings
      readonlyRootFilesystem = false
      user                   = "1000:1000"
    }
  ])

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-task"
    Service = var.service_name
    Type    = "ecs-task-definition"
  })
}

resource "aws_ecs_service" "service" {
  name             = "${local.name_prefix}-${var.service_name}"
  cluster          = var.ecs_cluster_id
  task_definition  = aws_ecs_task_definition.service.arn
  desired_count    = var.desired_count
  launch_type      = "FARGATE"
  platform_version = "LATEST"

  enable_execute_command = var.environment != "prod"

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.service.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.service.arn
    container_name   = var.service_name
    container_port   = var.container_port
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service.arn
  }

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-service"
    Service = var.service_name
    Type    = "ecs-service"
  })

  depends_on = [
    aws_lb_target_group.service,
    aws_iam_role_policy_attachment.ecs_task_execution_role_policy
  ]
}

resource "aws_security_group" "service" {
  name_prefix = "${local.name_prefix}-${var.service_name}-"
  description = "Security group for the ${var.service_name} ECS service"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [var.alb_security_group_id]
  }

  dynamic "ingress" {
    for_each = var.private_subnet_cidrs

    content {
      description = "Private service traffic from ${ingress.value}"
      from_port   = var.container_port
      to_port     = var.container_port
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-sg"
    Service = var.service_name
    Type    = "security-group"
  })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_target_group" "service" {
  name        = "${local.name_prefix}-${var.service_name}-tg"
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = var.health_check_path
    protocol            = "HTTP"
    port                = "traffic-port"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  stickiness {
    type            = "lb_cookie"
    cookie_duration = 86400
    enabled         = false
  }

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-tg"
    Service = var.service_name
    Type    = "target-group"
  })
}

resource "aws_lb_listener_rule" "service" {
  listener_arn = var.alb_listener_arn
  priority     = var.alb_listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service.arn
  }

  condition {
    path_pattern {
      values = ["/${var.service_name}", "/${var.service_name}/*"]
    }
  }

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-rule"
    Service = var.service_name
    Type    = "alb-listener-rule"
  })
}

# ECS Task Execution Role
resource "aws_iam_role" "ecs_task_execution_role" {
  name = "${local.name_prefix}-${var.service_name}-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-execution-role"
    Service = var.service_name
    Type    = "iam-role"
  })
}

# ECS Task Role (for application permissions)
resource "aws_iam_role" "ecs_task_role" {
  name = "${local.name_prefix}-${var.service_name}-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-task-role"
    Service = var.service_name
    Type    = "iam-role"
  })
}

# Policy for accessing Secrets Manager
resource "aws_iam_policy" "secrets_access" {
  name        = "${local.name_prefix}-${var.service_name}-secrets-access"
  description = "Policy for accessing secrets from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "kms:Decrypt"
        ]
        Resource = compact([
          var.db_secret_arn,
          var.jwt_secret_arn,
          var.kafka_sasl_jaas_secret_arn,
          var.secrets_kms_key_arn
        ])
      }
    ]
  })

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-secrets-policy"
    Service = var.service_name
    Type    = "iam-policy"
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_role_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_secrets_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = aws_iam_policy.secrets_access.arn
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "service" {
  name              = "/ecs/${local.name_prefix}/${var.service_name}"
  retention_in_days = 30

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-logs"
    Service = var.service_name
    Type    = "logging"
  })
}

# Service Discovery
resource "aws_service_discovery_service" "service" {
  name = var.service_name

  dns_config {
    namespace_id = var.service_discovery_namespace_id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  tags = merge(var.common_tags, {
    Name    = "${local.name_prefix}-${var.service_name}-discovery"
    Service = var.service_name
    Type    = "service-discovery"
  })
}
