resource "aws_codepipeline" "grocellery_pipeline" {
  name     = "${var.project_name}-pipeline"
  role_arn = aws_iam_role.codepipeline_role.arn

  artifact_store {
    location = aws_s3_bucket.codepipeline_artifacts.bucket
    type     = "S3"
  }

  stage {
    name = "Source"

    action {
      name             = "Source"
      category         = "Source"
      owner            = "AWS"
      provider         = "CodeCommit"
      version          = "1"
      output_artifacts = ["source_output"]

      configuration = {
        RepositoryName = var.project_name
        BranchName     = "main"
      }
    }
  }

  stage {
    name = "Build"

    action {
      name             = "Build"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["source_output"]
      output_artifacts = ["build_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_build.name
      }
    }
  }

  # --- DEV ENVIRONMENT ---
  stage {
    name = "Plan-Dev"

    action {
      name             = "Terraform-Plan-Dev"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["source_output"]
      output_artifacts = ["dev_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "dev", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "dev", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/dev", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Approve-Dev"

    action {
      name     = "Manual-Approval-Dev"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
    }
  }

  stage {
    name = "Apply-Dev"

    action {
      name            = "Terraform-Apply-Dev"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["dev_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf-apply.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf-apply.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "dev", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "dev", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/dev", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Quick-Test-Dev"

    action {
      name            = "Post-Deploy-Quick-Test-Dev"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["source_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_quick_test.name
        EnvironmentVariables = jsonencode([
          { name = "ENVIRONMENT", value = "dev", type = "PLAINTEXT" },
          { name = "SERVICES", value = "cart,order,product,summary", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Promote-Staging"

    action {
      name     = "Manual-Approval-Staging-Gate"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
    }
  }

  # --- STAGING ENVIRONMENT ---
  stage {
    name = "Plan-Staging"

    action {
      name             = "Terraform-Plan-Staging"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["source_output"]
      output_artifacts = ["staging_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "staging", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "staging", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/staging", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Approve-Staging"

    action {
      name     = "Manual-Approval-Staging"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
    }
  }

  stage {
    name = "Apply-Staging"

    action {
      name            = "Terraform-Apply-Staging"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["staging_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf-apply.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf-apply.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "staging", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "staging", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/staging", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Quick-Test-Staging"

    action {
      name            = "Post-Deploy-Quick-Test-Staging"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["source_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_quick_test.name
        EnvironmentVariables = jsonencode([
          { name = "ENVIRONMENT", value = "staging", type = "PLAINTEXT" },
          { name = "SERVICES", value = "cart,order,product,summary", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Promote-Prod"

    action {
      name     = "Manual-Approval-Prod-Gate"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
    }
  }

  # --- PRODUCTION ENVIRONMENT ---
  stage {
    name = "Plan-Prod"

    action {
      name             = "Terraform-Plan-Prod"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["source_output"]
      output_artifacts = ["prod_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "prod", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "prod", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/prod", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Approve-Prod"

    action {
      name     = "Manual-Approval-Prod"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
    }
  }

  stage {
    name = "Apply-Prod"

    action {
      name            = "Terraform-Apply-Prod"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["prod_plan_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_terraform.name
        BuildSpec   = "terraform/buildspec-tf-apply.yml"
        EnvironmentVariables = jsonencode([
          { name = "BUILDSPEC_OVERRIDE", value = "terraform/buildspec-tf-apply.yml", type = "PLAINTEXT" },
          { name = "TF_WORKSPACE", value = "prod", type = "PLAINTEXT" },
          { name = "TF_VAR_environment", value = "prod", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" },
          { name = "TF_STATE_BUCKET", value = aws_s3_bucket.terraform_state.bucket, type = "PLAINTEXT" },
          { name = "TF_STATE_KEY_PREFIX", value = "${var.project_name}/prod", type = "PLAINTEXT" },
          { name = "TF_STATE_LOCK_TABLE", value = aws_dynamodb_table.terraform_lock.name, type = "PLAINTEXT" }
        ])
      }
    }
  }

  stage {
    name = "Quick-Test-Prod"

    action {
      name            = "Post-Deploy-Quick-Test-Prod"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["source_output"]

      configuration = {
        ProjectName = aws_codebuild_project.grocellery_quick_test.name
        EnvironmentVariables = jsonencode([
          { name = "ENVIRONMENT", value = "prod", type = "PLAINTEXT" },
          { name = "SERVICES", value = "cart,order,product,summary", type = "PLAINTEXT" },
          { name = "PROJECT_NAME", value = var.project_name, type = "PLAINTEXT" },
          { name = "AWS_REGION", value = var.aws_region, type = "PLAINTEXT" }
        ])
      }
    }
  }

}

resource "aws_s3_bucket" "codepipeline_artifacts" {
  bucket = "grocellery-codepipeline-artifacts"

  versioning {
    enabled = true
  }

  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        sse_algorithm = "AES256"
      }
    }
  }
}

resource "aws_s3_bucket_public_access_block" "codepipeline_artifacts" {
  bucket = aws_s3_bucket.codepipeline_artifacts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "codepipeline_artifacts_tls" {
  bucket = aws_s3_bucket.codepipeline_artifacts.id

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Sid       = "DenyInsecureTransport",
        Effect    = "Deny",
        Principal = "*",
        Action    = "s3:*",
        Resource = [
          aws_s3_bucket.codepipeline_artifacts.arn,
          "${aws_s3_bucket.codepipeline_artifacts.arn}/*"
        ],
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

resource "random_id" "terraform_state_suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "terraform_state" {
  bucket = "${var.project_name}-tf-state-${random_id.terraform_state_suffix.hex}"

  versioning {
    enabled = true
  }

  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        sse_algorithm = "AES256"
      }
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "terraform_state_tls" {
  bucket = aws_s3_bucket.terraform_state.id

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Sid       = "DenyInsecureTransport",
        Effect    = "Deny",
        Principal = "*",
        Action    = "s3:*",
        Resource = [
          aws_s3_bucket.terraform_state.arn,
          "${aws_s3_bucket.terraform_state.arn}/*"
        ],
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

resource "aws_dynamodb_table" "terraform_lock" {
  name         = "${var.project_name}-tf-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}

resource "aws_codebuild_project" "grocellery_build" {
  name          = "grocellery-build"
  description   = "Build project for the Grocery E-Commerce Platform"
  build_timeout = "30"
  service_role  = aws_iam_role.codebuild_app_build_role.arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    compute_type                = "BUILD_GENERAL1_SMALL"
    image                       = "aws/codebuild/standard:7.0"
    type                        = "LINUX_CONTAINER"
    privileged_mode             = true
    image_pull_credentials_type = "SERVICE_ROLE"

    environment_variable {
      name  = "AWS_ACCOUNT_ID"
      value = data.aws_caller_identity.current.account_id
    }
    environment_variable {
      name  = "AWS_REGION"
      value = var.aws_region
    }
    environment_variable {
      name  = "AWS_DEFAULT_REGION"
      value = var.aws_region
    }
    environment_variable {
      name  = "IMAGE_TAG"
      value = "latest"
    }
    environment_variable {
      name  = "PROJECT_NAME"
      value = var.project_name
    }
    environment_variable {
      name  = "TARGET_ENVIRONMENTS"
      value = "dev staging prod"
    }
    environment_variable {
      name  = "SCAN_SEVERITY"
      value = "CRITICAL,HIGH"
    }
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = "buildspec.yml"
  }
}

resource "aws_codebuild_project" "grocellery_terraform" {
  name          = "grocellery-terraform"
  description   = "Terraform plan and apply for the Grocery E-Commerce Platform"
  build_timeout = "30"
  service_role  = aws_iam_role.codebuild_terraform_role.arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    compute_type = "BUILD_GENERAL1_SMALL"
    image        = "aws/codebuild/standard:7.0"
    type         = "LINUX_CONTAINER"

  }

  source {
    type      = "CODEPIPELINE"
    buildspec = "terraform/buildspec-tf.yml"
  }
}

resource "aws_codebuild_project" "grocellery_quick_test" {
  name          = "grocellery-quick-test-checks"
  description   = "Post-deployment quick test and canary checks with rollback"
  build_timeout = "15"
  service_role  = aws_iam_role.codebuild_quick_test_role.arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    compute_type = "BUILD_GENERAL1_SMALL"
    image        = "aws/codebuild/standard:7.0"
    type         = "LINUX_CONTAINER"

    environment_variable {
      name  = "PROJECT_NAME"
      value = var.project_name
    }

    environment_variable {
      name  = "AWS_REGION"
      value = var.aws_region
    }
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = "terraform/buildspec-quick-test.yml"
  }
}

# IAM Roles and Policies

resource "aws_iam_role" "codepipeline_role" {
  name = "grocellery-codepipeline-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "codepipeline.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role" "codebuild_app_build_role" {
  name = "grocellery-codebuild-app-build-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "codebuild.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role" "codebuild_terraform_role" {
  name = "grocellery-codebuild-terraform-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "codebuild.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role" "codebuild_quick_test_role" {
  name = "grocellery-codebuild-quick-test-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "codebuild.amazonaws.com",
        }
      }
    ]
  })
}

# --- IAM POLICIES ---

resource "aws_iam_policy" "codepipeline_policy" {
  name        = "grocellery-codepipeline-policy"
  description = "Policy for the Grocellery CodePipeline role"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "s3:GetObject",
          "s3:GetObjectVersion",
          "s3:GetBucketVersioning",
          "s3:PutObject"
        ],
        Resource = [
          aws_s3_bucket.codepipeline_artifacts.arn,
          "${aws_s3_bucket.codepipeline_artifacts.arn}/*"
        ]
      },
      {
        Effect   = "Allow",
        Action   = ["codebuild:StartBuild", "codebuild:BatchGetBuilds"],
        Resource = "*"
      },
      {
        Effect   = "Allow",
        Action   = ["codecommit:GetBranch", "codecommit:GetCommit", "codecommit:UploadArchive", "codecommit:GetUploadArchiveStatus", "codecommit:CancelUploadArchive"],
        Resource = "arn:aws:codecommit:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${var.project_name}"
      }
    ]
  })
}

resource "aws_iam_policy" "codebuild_app_build_policy" {
  name        = "grocellery-codebuild-app-build-policy"
  description = "Policy for the Grocery E-Commerce Platform build project"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect   = "Allow",
        Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
        Resource = "*"
      },
      {
        Effect   = "Allow",
        Action   = "ecr:GetAuthorizationToken",
        Resource = "*"
      },
      {
        Effect = "Allow",
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage"
        ],
        Resource = "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-*"
      }
    ]
  })
}

resource "aws_iam_policy" "codebuild_secrets_manager_policy" {
  name        = "grocellery-codebuild-secrets-manager-policy"
  description = "Allow Terraform CodeBuild to manage the OIDC configuration secret"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "secretsmanager:CreateSecret",
          "secretsmanager:DeleteSecret",
          "secretsmanager:DescribeSecret",
          "secretsmanager:GetSecretValue",
          "secretsmanager:PutSecretValue",
          "secretsmanager:UpdateSecret",
          "secretsmanager:TagResource",
          "secretsmanager:UntagResource"
        ],
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${local.name_prefix}/*"
      }
    ]
  })
}

resource "aws_iam_policy" "codebuild_quick_test_policy" {
  name        = "grocellery-codebuild-quick-test-policy"
  description = "Policy for quick test/canary checks and rollback"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect   = "Allow",
        Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
        Resource = "*"
      },
      {
        Effect = "Allow",
        Action = [
          "ecs:DescribeServices",
          "ecs:UpdateService",
          "ecs:DescribeTaskDefinition"
        ],
        Resource = "*"
      },
      {
        Effect   = "Allow",
        Action   = ["elasticloadbalancing:DescribeTargetGroups", "elasticloadbalancing:DescribeTargetHealth"],
        Resource = "*"
      }
    ]
  })
}

# --- POLICY ATTACHMENTS ---

resource "aws_iam_role_policy_attachment" "codepipeline_attachment" {
  role       = aws_iam_role.codepipeline_role.name
  policy_arn = aws_iam_policy.codepipeline_policy.arn
}

resource "aws_iam_role_policy_attachment" "codebuild_app_build_attachment" {
  role       = aws_iam_role.codebuild_app_build_role.name
  policy_arn = aws_iam_policy.codebuild_app_build_policy.arn
}

resource "aws_iam_role_policy_attachment" "terraform_secrets_manager_attachment" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = aws_iam_policy.codebuild_secrets_manager_policy.arn
}

resource "aws_iam_policy" "codebuild_runtime_configuration_policy" {
  name        = "grocellery-codebuild-runtime-configuration-policy"
  description = "Allow Terraform CodeBuild to read per-environment runtime parameters"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect   = "Allow",
        Action   = ["ssm:GetParameter"],
        Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project_name}/*/runtime-configuration"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "terraform_runtime_configuration_attachment" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = aws_iam_policy.codebuild_runtime_configuration_policy.arn
}

resource "aws_iam_role_policy_attachment" "codebuild_quick_test_attachment" {
  role       = aws_iam_role.codebuild_quick_test_role.name
  policy_arn = aws_iam_policy.codebuild_quick_test_policy.arn
}

# Attach AWS managed policies for Terraform access
resource "aws_iam_role_policy_attachment" "terraform_ecs" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonECS_FullAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_ec2" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2FullAccess" # For VPC, Security Groups, etc.
}

resource "aws_iam_role_policy_attachment" "terraform_rds" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonRDSFullAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_elb" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_ecr" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_s3" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_iam" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/IAMFullAccess" # WARNING: Still very permissive.
}

resource "aws_iam_role_policy_attachment" "terraform_logs" {
  role       = aws_iam_role.codebuild_terraform_role.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchLogsFullAccess"
}
