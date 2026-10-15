# OIDC resource-server configuration shared by all microservices
resource "aws_secretsmanager_secret" "oidc_config" {
  name        = "${local.name_prefix}/oidc-config"
  description = "OIDC issuer and audience for OAuth2 resource servers"

  kms_key_id = aws_kms_key.secrets.arn

  replica {
    region = var.aws_region
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-oidc-config"
    Type = "secret"
  })
}

resource "aws_secretsmanager_secret_version" "oidc_config_version" {
  secret_id = aws_secretsmanager_secret.oidc_config.id
  secret_string = jsonencode({
    issuer_uri = var.jwt_issuer_uri
    audience   = var.jwt_audience
  })
}

# KMS Key for Secrets Manager
resource "aws_kms_key" "secrets" {
  description             = "KMS key for Secrets Manager encryption"
  deletion_window_in_days = var.environment == "prod" ? 30 : 7
  enable_key_rotation     = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow Secrets Manager to use the key"
        Effect = "Allow"
        Principal = {
          Service = "secretsmanager.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:DescribeKey",
          "kms:Encrypt",
          "kms:GenerateDataKey*",
          "kms:ReEncrypt*"
        ]
        Resource = "*"
      },
    ]
  })

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-secrets-kms"
    Type = "encryption"
  })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${local.name_prefix}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}
