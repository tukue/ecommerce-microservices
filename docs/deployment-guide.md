# Deployment Guide

## Current Assets

- Dockerfiles for each service.
- `microservices/docker-compose.yml` for local multi-service execution.
- `prometheus.yml` for local Micrometer scraping.
- Terraform modules for VPC, ALB, ECS, RDS, monitoring, and secrets.
- CodeBuild buildspec with Maven verification, Docker image builds, SBOM generation, Gitleaks, Hadolint, and Trivy scanning.
- GitHub Actions workflows for Maven and microservice CI.

## Recommended Deployment Flow

Define the backend capability, API/data contracts, and operational acceptance
criteria before implementation using the
[backend feature-definition template](backend-feature-definition-before-deployment.md).

1. Run unit and integration tests.
2. Build immutable Docker images tagged by commit SHA.
3. Generate SBOMs.
4. Run secret, dependency, container, and IaC scans.
5. Push images to ECR.
6. Run Terraform plan.
7. Require approval for production Terraform apply.
8. Deploy to ECS using rolling updates.
9. Run smoke tests against `/actuator/health` and a small authenticated API path.

## Production Recommendations

- Add Flyway or Liquibase migrations and set `ddl-auto=validate`.
- Add readiness/liveness probes if Kubernetes manifests are introduced.
- Use Helm only if Kubernetes becomes the target platform. ECS/Terraform is currently enough.
- Add Maven dependency caching in GitHub Actions and CodeBuild.
- Add OWASP Dependency-Check or Snyk/Dependabot alerts for dependency drift.
