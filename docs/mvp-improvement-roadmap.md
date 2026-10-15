# MVP Improvement Roadmap

## Purpose

This document records the read-only backend review completed on 2026-09-05. It is a
practical, MVP-scoped roadmap for improving reliability and security without changing
the current service boundaries or introducing unnecessary platform complexity.

## Current Architecture

The platform contains four Spring Boot services:

| Service | Responsibility | Primary dependency |
| --- | --- | --- |
| `product-service` | Catalog, price, availability, and stock data | Product PostgreSQL database |
| `cart-service` | Customer carts and product snapshots | Product Service over REST |
| `order-service` | Checkout, order lines, lifecycle, and stored event intent | Cart Service over REST; Order PostgreSQL database |
| `ledger-service` | Asynchronous order summaries and receipts | Kafka; Summary PostgreSQL database |

Checkout reads a cart snapshot, writes an order and `order_event_store` record in one
transaction, then a scheduled relay publishes `order.created.v1`. Ledger Service
consumes the event, retries bounded failures, sends exhausted records to
`order.created.v1.failed`, and uses a unique order ID to tolerate normal redelivery.

## Confirmed Strengths

- Checkout calculates trusted totals from cart snapshots rather than client totals.
- Order persistence and event intent share one transaction.
- Kafka publishing uses idempotent producer settings, a bounded timeout, retries, and
  leased outbox records.
- The summary consumer commits only after successful listener processing and has a
  failed-letter queue for exhausted failures.
- Service APIs use DTO validation, typed domain errors, Actuator, Prometheus scraping,
  Docker health checks, and CI smoke tests.

## MVP Release Blockers

| Priority | Finding | Evidence | Smallest safe improvement |
| --- | --- | --- | --- |
| P0 | Static `user`/`password` login exists in every service. | `microservices/*/controller/AuthController.java` | Remove service-issued demo tokens outside development and validate one approved identity source. |
| P0 | Terraform creates different JWT secrets for each service although browser and service-to-service calls need the same bearer token accepted across services. | `terraform/secrets.tf`, `terraform/services.tf` | Use one shared issuer/JWKS; use one shared verification secret only as a temporary transition. |
| P0 | Summary endpoints do not enforce ownership and allow authenticated clients to create summaries directly. | `ledger-service/.../LedgerController.java` | Make writes internal/Kafka-only and authorize summary/receipt reads by customer identity. |
| P0 for ECS deployment | ECS task definitions do not inject required CORS or Kafka bootstrap configuration. | `terraform/modules/ecs/main.tf`, `application-prod.properties` | Supply required runtime variables and select/provision a managed Kafka service before cloud release. |

## Important MVP Reliability Work

| Priority | Finding | Smallest safe improvement |
| --- | --- | --- |
| P1 | No Flyway/Liquibase migrations; Docker and dev use Hibernate `ddl-auto=update`. | Add Flyway migrations per service and retain `ddl-auto=validate` outside tests. |
| P1 | Checkout has no idempotency key. | Store an idempotency key with the customer and return the original order for repeated requests. |
| P1 | Stock is checked when adding to cart but is not reserved/decremented at checkout. | Explicitly label it advisory or add a narrow idempotent reservation/decrement endpoint. |
| P1 | Outbox lifecycle has logs but no producer/backlog metrics. | Add published, failed, terminal-failed, and pending-event metrics. |
| P1 | Event schema has no event version, correlation ID, currency, or decimal total. | Add metadata additively while retaining `order.created.v1` compatibility. |
| P2 | No PostgreSQL/Kafka Testcontainers tests cover the critical event flow. | Add focused order-to-summary integration tests without timing sleeps. |
| P2 | Cart/order mutable aggregates have no optimistic locking. | Add `@Version` and return `409` for stale writes. |

## Implementation Stages

### Stage 1: Security and Ownership

**Objective:** make one customer identity valid across service boundaries and protect
customer summaries.

- Replace demo authentication outside development.
- Configure common JWT issuer/key verification across all services.
- Restrict summary writes to internal event processing.
- Require summary and receipt ownership checks.
- Add cross-service authentication and unauthorized-summary tests.

**Acceptance:** a single customer token works across cart, order, and summary APIs;
another customer cannot read or create that customer's summary.

### Stage 2: Cloud Runtime Configuration

**Objective:** make ECS deployment configuration complete and deterministic.

- Inject `CORS_ALLOWED_ORIGINS`, Kafka bootstrap/security settings, and service URLs.
- Provision or explicitly select managed Kafka before enabling Order and Ledger Services.
- Add a cloud-like container startup smoke test.

**Acceptance:** all services start healthy with production-like configuration.

### Stage 3: Database Migrations

**Objective:** make schema changes repeatable.

- Add Flyway with a baseline migration for each service-owned schema.
- Remove non-test Hibernate schema mutation.
- Test migrations against clean PostgreSQL databases.

**Acceptance:** every service starts against a clean migrated database with
`ddl-auto=validate`.

### Stage 4: Checkout Correctness

**Objective:** make retries and inventory behavior explicit.

- Add checkout idempotency keys.
- Decide whether stock is advisory or reserved during checkout.
- If reserving stock, make the reservation idempotent and test compensation on failure.

**Acceptance:** duplicate checkout requests do not create duplicate orders, and the
stock guarantee is documented and tested.

### Stage 5: Workflow Observability and Integration Tests

**Objective:** make asynchronous failures visible and reproducible.

- Add outbox and Kafka lifecycle metrics.
- Add PostgreSQL/Kafka Testcontainers coverage for order creation, relay failure,
  duplicate delivery, and failed-letter handling.
- Document failed-letter replay as an operator action.

**Acceptance:** CI validates the real order-to-summary flow and operators can identify
pending or terminally failed events.

## Implementation Status

- **Stage 1 (Security):** implemented. Single shared issuer/JWKS across services, demo
  auth removed outside dev, summary writes restricted to event processing, ownership
  checks with cross-service authentication tests.
- **Stage 2 (Cloud Runtime):** ECS task definitions now inject the explicit CORS
  allowlist, TLS/SASL Kafka settings for order and summary, and Cloud Map service URLs;
  all services use the `prod` profile and RDS-managed credentials. The shared private
  DNS namespace and service-to-service security-group access are wired, and the
  CodeBuild plan/apply jobs read environment-specific runtime configuration from
  Parameter Store. The broker remains externally managed and its endpoints and CORS
  origins must be configured in the per-environment runtime parameter; a deployed
  cloud-like container smoke test is still outstanding.
- **Stage 3 (Migrations):** implemented. Flyway `V1__init_schema.sql` per service
  (plus `product-service V2__seed_demo_products.sql`); docker/prod use
  `ddl-auto=validate` with Flyway enabled; dev/test keep Hibernate-managed H2.
- **Stage 4 (Checkout Correctness):** implemented. Checkout accepts an
  `Idempotency-Key` header or body field, stores a unique `(user_id, idempotency_key)`,
  and replays the original order for duplicates. Stock is now reserved (not advisory):
  `product-service` exposes idempotent, pessimistic-lock guarded
  `POST /products/{productId}/reservations` and `DELETE /products/reservations/{reservationKey}`
  endpoints backed by a `stock_reservation` table; `order-service` reserves every cart
  line before writing the order and compensates (releases) on any failure — a later
  line failure, a persistence/event failure, or the generic error path. Retrying a
  failed checkout re-activates the released reservation instead of double-decrementing.
  Concurrency is handled by a `PESSIMISTIC_WRITE` lock on the product row plus a unique
  reservation-key index as backstop; counters (`checkout.reservations.reserved|released`)
  and e2e coverage (stock-exhaustion conflict + compensatory release) are included.
- **Stage 5 (Observability):** implemented. Producer metrics added
  (`outbox.events.published|failed|terminal_failed`, `outbox.events.pending` gauge);
  `OrderCreatedEvent` now carries `correlationId` and `currency` while keeping the
  `order.created.v1` event version. End-to-end Testcontainers coverage
  (`microservices/e2e-tests`, PostgreSQL + Redpanda) validates happy-path relay,
  idempotent checkout replay, and duplicate delivery without double summaries;
  failed-letter replay operator docs remain outstanding (P2).
- **Mutable Aggregate Locking:** implemented for cart and order aggregates. Both now
  use JPA optimistic locking and map stale-write failures to `409 Conflict` responses
  so concurrent updates fail explicitly instead of silently overwriting each other.
- **Cart Checkout Lifecycle (claim-first + compensation):** implemented. `OrderService`
  claims the cart (`markCheckedOut`, `@Version` gate) *before* persisting the order, so
  the optimistic-locked cart is the atomic single-winner that makes a second order for
  the same cart impossible; stock is reserved first and every failure is compensated
  (reservations released, and on order/event write failure the cart is reopened via the
  new `markOpen` + `POST /cart/{cartId}/open`, `SCOPE_cart:write`). A same-key race past
  the replay check is resolved by the existing `(user_id, idempotency_key)` unique index:
  `DataIntegrityViolationException` is re-queried and the winner's order is returned as an
  idempotent replay instead of a 500. The guarantee is backstopped at the database by
  `order-service V3__one_order_per_cart.sql` (`uk_orders_cart_id` unique index). Covered
  by in-order unit tests (claim before save, revert-failure does not mask the origin
  error) and a stateful e2e cart stub that throws `CheckoutCartAlreadyCheckedOutException`
  on a second claim, so the guard executes against real Postgres + Redpanda.

## Deliberate Deferrals

- Saga frameworks, distributed transactions, and additional messaging technologies.
- New payment or fulfilment services before a concrete business workflow requires them.
- Service mesh, Kubernetes operators, schema registry, and multi-region deployment.
- Shared backend libraries until service contracts stabilize.

## New-Service Checklist

- Clear business capability, owner, API/event contract, and data ownership.
- Externalized configuration, startup validation, timeouts, and safe retry rules.
- DTO validation, typed errors, authorization, and idempotency where required.
- Health checks, structured logs, correlation IDs, and essential metrics.
- Unit, API, and critical integration tests.
- Docker Compose configuration and README updates.
