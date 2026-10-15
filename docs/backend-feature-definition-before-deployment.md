# Backend Feature Definition Before Deployment

Use this template to define a backend feature before implementation and before
deploying it to a production-like environment. It makes the customer outcome,
service ownership, API and data contracts, operational impact, and release gates
clear. Copy it into a feature-specific document and replace each prompt; mark
items out of scope with a reason.

For the matching frontend brief, see
[`feature-definition-before-vercel.md`](feature-definition-before-vercel.md).
For backend implementation conventions, see
[`backend-engineering-specification.md`](backend-engineering-specification.md).
For the current Render and Vercel rollout sequence, see
[`DEPLOYMENT_RENDER_VERCEL.md`](DEPLOYMENT_RENDER_VERCEL.md).

## Feature brief template

### Summary

- **Feature name:**
- **Owner:**
- **Status:** Draft / Approved / In progress / Ready to deploy
- **Target release and environment:** Local / Staging / Production
- **Related issue, frontend brief, or specification:**

### Problem and value

- **Who has the problem?**
- **What are they trying to do?**
- **What backend capability is missing or unreliable today?**
- **Expected customer or business value:**
- **How will success be measured?**

### Scope and service ownership

- **In scope:**
- **Out of scope:**
- **Owning service and reason:**
- **Other services affected:**
- **Assumptions, constraints, and dependencies:**

Keep each service authoritative for its own domain data. Identify any data
copied across service boundaries and why that copy is needed.

### User and system behavior

Describe the customer-visible journey and the backend behavior that supports
each step. Include failure, retry, and recovery paths.

| Priority | User or system scenario | Expected backend outcome / acceptance criteria |
| --- | --- | --- |
| P1 | Given [context], when [request/event] occurs, then [observable result]. | [Persisted result, response or event, and recovery behavior.] |

### API and event contracts

- **HTTP endpoints:** method, path, authentication, request/response examples,
  status codes, validation rules, and error shape.
- **Authorization and ownership:** trusted identity source and resource access
  rules for reads and writes.
- **Compatibility:** affected consumers and backward/forward compatibility
  requirements.
- **Events:** topic and version, key, payload, delivery semantics, consumer
  behavior, retry limits, and failed-message handling; mark not applicable if
  the feature emits or consumes no events.
- **Idempotency and concurrency:** how duplicate requests, retries, and
  competing updates are handled.
- **Limits and timeouts:** payload size, pagination, rate limits, and
  downstream call timeouts as applicable.

Link to the canonical API or event contract rather than duplicating it here.

### Data and persistence

- **Entities or records changed:**
- **Source of truth and ownership:**
- **Schema/index changes:**
- **Migration strategy:** forward migration, compatibility window, and rollback
  or roll-forward approach.
- **Retention, deletion, and privacy requirements:**
- **Consistency and transaction boundaries:**

Production schema changes must use the repository's migration strategy; do not
rely on Hibernate schema creation or update to change production data.

### Security and reliability

- **Authentication and authorization changes:**
- **Input validation and abuse cases:**
- **Sensitive data and secrets:** where stored and how they are kept out of
  source control, logs, and API responses.
- **Failure behavior:** dependency unavailable, timeout, invalid state, and
  partial completion.
- **Resilience:** retry safety, bounded retries/timeouts, and circuit-breaking
  needs.
- **Operational signals:** logs, metrics, traces, and alerts required to detect
  failures without logging secrets or unnecessary personal data.

### Verification plan

- [ ] Unit tests cover business rules and validation.
- [ ] API tests cover success, error responses, and authorization/ownership.
- [ ] Persistence tests cover required migrations and data constraints.
- [ ] Downstream or event integration tests cover relevant failure and retry
      behavior.
- [ ] Formatting, static analysis, full relevant test suite, and production
      artifact build pass.
- [ ] Container image builds and required image/security scans pass.
- [ ] Staging smoke tests cover the primary flow and a relevant failure path.

### Backend deployment gate

Complete the applicable items before production deployment:

- [ ] Product/API contract and acceptance criteria are approved by owners of
      affected services and consumers.
- [ ] The affected service's Docker build, health check, resource requirements,
      and runtime port are correct for the target platform.
- [ ] Production profile and required configuration are documented; secret
      values are configured in the deployment provider, not in Git.
- [ ] Database is provisioned, reachable privately where supported, backed up,
      and configured with the correct service-owned credentials.
- [ ] Schema migrations are tested against an existing database and are
      compatible with the rollout order.
- [ ] Identity-provider issuer/audience, TLS, CORS origins, and service-to-
      service access rules are configured for the target environment.
- [ ] Kafka topics, credentials, TLS/SASL settings, and consumer retry/dead-
      letter handling are ready when the feature uses Kafka.
- [ ] Required upstream and downstream services are healthy before traffic is
      routed to the new deployment.
- [ ] CI quality gates and security scans pass for the exact commit/image being
      deployed.
- [ ] Smoke tests, dashboards/alerts, operational owner, and rollback or
      roll-forward steps are ready.
- [ ] Frontend/BFF deployment dependencies and API compatibility are agreed;
      deploy backend changes first when required by the rollout plan.

**Release decision:** GO / NO-GO
**Decision owner and date:**
**Deployed commit/image and environment:**
**Known risks, rollback trigger, or follow-up work:**
