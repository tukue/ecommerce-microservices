# Terraform Infrastructure for Grocery E-Commerce Platform

This directory contains Terraform configurations to deploy the Grocery E-Commerce Platform to AWS using a modular approach.

## Architecture

The infrastructure includes:
- **VPC** with public/private subnets across 2 AZs
- **RDS PostgreSQL** instances (one per microservice)
- **ECS Fargate** cluster for containerized services
- **Application Load Balancer** for traffic routing
- **ECR** repositories for container images
- **CloudWatch** for logging and monitoring

## Directory Structure

```
terraform/
├── modules/                    # Reusable Terraform modules
│   ├── vpc/                   # VPC and networking
│   ├── rds/                   # PostgreSQL databases
│   ├── alb/                   # Application Load Balancer
│   └── ecs/                   # ECS cluster and services
├── environments/              # Environment-specific configurations
│   └── dev/                   # Development environment
└── README.md
```

## Prerequisites

1. **AWS CLI** configured with appropriate credentials
2. **Terraform** >= 1.0 installed
3. **Docker** for building container images

## Quick Start

### 1. Configure Variables

```bash
cd terraform
```

Before planning, create the environment's runtime-configuration parameter described
below and configure the required AWS variables in `terraform.tfvars` or through
environment variables. Then run the root Terraform configuration:

```bash
terraform init
terraform workspace select dev || terraform workspace new dev
terraform plan
```

Set `environment` to `staging` or `prod` when planning those environments. Configure
the required deployment inputs:

- Adjust `aws_region` if needed
- Modify resource sizes based on requirements
- Create one Systems Manager Parameter Store `String` parameter per environment:
  `/<project>/<environment>/runtime-configuration`, such as
  `/grocery-ecommerce-platform/dev/runtime-configuration`.

The broker is not provisioned by this Terraform stack. Order and summary tasks require
reachable bootstrap endpoints. Use `kafka_security_protocol = "SSL"` for TLS-only
brokers, or `"SASL_SSL"` with `kafka_sasl_mechanism` and
`kafka_sasl_jaas_secret_arn` referencing a Secrets Manager secret whose JSON contains
`jaas_config`. Kafka security variables are injected only into order and summary tasks.
Keep Kafka credentials in Secrets Manager, not in Parameter Store or Terraform files.

Each Parameter Store value is JSON and contains only non-secret runtime settings:

```json
{
  "cors_allowed_origins": "https://shop.example.com",
  "kafka_bootstrap_servers": "broker.prod.internal:9094",
  "kafka_security_protocol": "SSL"
}
```

For SASL_SSL, also provide `kafka_sasl_mechanism` and
`kafka_sasl_jaas_secret_arn`. The Terraform CodeBuild role reads the matching
environment parameter during plan/apply.

RDS manages its master password in Secrets Manager. ECS tasks read the generated
credential secret rather than relying on a separately maintained password.

All ECS services use the Spring `prod` profile because they run against RDS and require
the configured OIDC issuer, regardless of the Terraform environment name. Cart and
product service URLs are wired through a shared Cloud Map private DNS namespace, and
private-subnet traffic is allowed between the services for those calls.

### 2. Initialize Terraform

```bash
terraform init
```

> State is stored locally by default to keep `terraform plan` runnable without remote backends. Configure a backend in `versions.tf` if you need shared state in CI/CD.

### 3. Plan Deployment

```bash
terraform plan
```

### 4. Deploy Infrastructure

```bash
terraform apply
```

## Module Details

### VPC Module (`modules/vpc/`)
- Creates VPC with DNS support
- Public subnets for ALB
- Private subnets for ECS and RDS
- Internet Gateway and NAT Gateway
- Route tables and associations

### RDS Module (`modules/rds/`)
- PostgreSQL 15.4 instances
- One database per microservice
- Multi-AZ for production
- Automated backups
- Security groups for database access

### ALB Module (`modules/alb/`)
- Application Load Balancer
- Target groups for each service
- Path-based routing (`/cart/*`, `/order/*`, etc.)
- Health checks on `/actuator/health`

### ECS Module (`modules/ecs/`)
- Fargate cluster with Container Insights
- Task definitions for each service
- Auto-scaling capabilities
- CloudWatch logging
- Service discovery

## Environment Configuration

### Development (`environments/dev/`)
- Small instance sizes (db.t3.micro, 256 CPU, 512 MB)
- Single task per service
- Cost-optimized settings

### Adding New Environments

1. Create new directory: `environments/staging/`
2. Copy files from `dev/` environment
3. Adjust variables for staging requirements
4. Deploy with environment-specific state

## Deployment Process

### CI/CD promotion flow

- **CodePipeline stages per environment**: dev → staging → prod with Terraform plan, manual approval, and apply gates for each hop.
- **Drift-aware plans**: `terraform plan -detailed-exitcode` runs in workspace-scoped state so drift or pending changes surface before approvals.
- **Workspace-isolated state**: CodeBuild selects/creates the `TF_WORKSPACE` passed by the pipeline, keeping state keys per environment for clean rollbacks.

### 1. Build and Push Images

```bash
# Build images for each service
docker build -t cart-service ./microservices/cart-service
docker build -t order-service ./microservices/order-service
docker build -t product-service ./microservices/product-service
docker build -t ledger-service ./microservices/ledger-service

# Tag and push to ECR (after terraform apply)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com

docker tag cart-service:latest <account-id>.dkr.ecr.us-east-1.amazonaws.com/grocery-ecommerce-platform-dev-cart:latest
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/grocery-ecommerce-platform-dev-cart:latest
```

### 2. Update ECS Services

After pushing new images, ECS services will automatically deploy new tasks.

## Accessing Services

After deployment, services are accessible through the ALB:

- Cart Service: `http://<alb-dns-name>/cart/`
- Order Service: `http://<alb-dns-name>/order/`
- Product Service: `http://<alb-dns-name>/product/`
- Ledger Service: `http://<alb-dns-name>/summary/`

## Monitoring

- **CloudWatch Logs**: `/ecs/grocery-ecommerce-platform-dev/<service>`
- **ECS Console**: Monitor service health and scaling
- **RDS Console**: Database performance metrics

## Security

- Services run in private subnets
- Database access restricted to ECS security group
- ALB handles SSL termination (add certificate for HTTPS)
- IAM roles follow least privilege principle

## Cost Optimization

### Development Environment (~$140/month)
- db.t3.micro RDS instances
- Fargate with minimal CPU/memory
- Single AZ deployment

### Production Considerations
- Multi-AZ RDS deployment
- Larger instance sizes
- Auto-scaling policies
- Reserved instances for cost savings

## Troubleshooting

### Common Issues

1. **ECS Tasks Failing to Start**
   - Check CloudWatch logs
   - Verify ECR image exists
   - Confirm database connectivity

2. **ALB Health Checks Failing**
   - Ensure `/actuator/health` endpoint is accessible
   - Check security group rules
   - Verify container port mapping

3. **Database Connection Issues**
   - Confirm RDS security group allows ECS access
   - Check database credentials
   - Verify subnet routing

### Useful Commands

```bash
# Check ECS service status
aws ecs describe-services --cluster grocery-ecommerce-platform-dev-cluster --services grocery-ecommerce-platform-dev-cart-service

# View CloudWatch logs
aws logs tail /ecs/grocery-ecommerce-platform-dev/cart --follow

# Check ALB target health
aws elbv2 describe-target-health --target-group-arn <target-group-arn>
```

## Cleanup

To destroy all resources:

```bash
terraform destroy
```

**Warning**: This will delete all data including databases. Ensure you have backups if needed.

## Next Steps

1. **CI/CD Pipeline**: Integrate with GitHub Actions for automated deployments
2. **SSL Certificate**: Add ACM certificate for HTTPS
3. **Domain Name**: Configure Route 53 for custom domain
4. **Monitoring**: Add CloudWatch dashboards and alarms
5. **Secrets Management**: Use AWS Secrets Manager for sensitive data
