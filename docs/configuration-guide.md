# Configuration Guide

## Profiles

- `test`: H2 in-memory database, real asymmetric JWT validation against test-only keys (never network), generated signing values.
- `dev`: local H2 database and local ports, embedded demo identity provider mints RS256 tokens and serves OIDC discovery + JWKS (`/.well-known/*`, `/auth/login`).
- `docker`: Docker Compose PostgreSQL databases for local smoke testing. It uses Postgres trust auth and blank local-only credentials so CI can start and tear down the stack without committed secrets. JWT validation stays enabled; when no external `JWT_ISSUER_URI` is supplied, each service runs its embedded demo identity provider so the stack is self-contained.
- `prod`: PostgreSQL, required externalized secrets, `ddl-auto=validate`, health details restricted, demo auth disabled.

## Required Production Configuration

Use environment variables or a secret manager for:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `JWT_ISSUER_URI` — OIDC issuer URL (e.g. your IdP realm). Each service resolves its JWKS from `{JWT_ISSUER_URI}/.well-known/openid-configuration`.
- `JWT_AUDIENCE` — required JWT audience; each microservice expects the shared `grocery-api` audience claim.

Unresolved `JWT_ISSUER_URI`/`JWT_AUDIENCE` fail application startup in `prod` (fail-fast, no runtime fallback). The `docker` profile is standalone: when `JWT_ISSUER_URI` is not supplied it falls back to the embedded demo issuer, so Compose smoke stacks start without an external IdP. The shared audience is one value, e.g. `grocery-api`.

Recommended production settings:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.h2.console.enabled=false
management.endpoint.health.show-details=when_authorized
management.endpoints.web.exposure.include=health,info,prometheus
security.jwt.demo-enabled=false
security.jwt.issuer-uri=${JWT_ISSUER_URI}
security.jwt.audience=${JWT_AUDIENCE}
```

## Configuring the client-facing Identity Provider

All four microservices are OAuth2 resource servers. In production/docker they fetch the IdP's signature keys from `JWT_ISSUER_URI` OIDC discovery and validate every token's signature, algorithm (RS256), issuer, audience, timestamps, and `sub` claim. Tokens must contain the negotiated scope claim; scopes such as `cart:read`, `cart:write`, `order:read`, `order:write`, `ledger:read`, and `product:admin` gate endpoints via method security (`@PreAuthorize`).

Inter-service calls (order → cart) forward the caller's bearer token so the downstream service enforces the same ownership rules; no shared-secret internal headers are used.

### Production identity is enforced at startup

In the `prod` profile every service runs `ProductionIdentityGuard`, which fails startup
(without printing configured values) when the development identity mechanism is enabled
(`security.jwt.demo-enabled=true`), the issuer is missing or not HTTPS, or the audience is
blank. The gateway has the same guard for `gateway.jwt.*` and now ships an
`application-prod.properties` requiring `JWT_ISSUER_URI`, `JWT_AUDIENCE`, upstream service
URLs, explicit CORS origins, and a TLS Redis connection.

## Storefront (BFF) production identity

The Node BFF owns the browser session and, in production, performs an OIDC authorization
code flow with PKCE against the external provider. Provider selection (E01) is external;
the BFF is vendor-neutral and discovers endpoints from the issuer.

| Variable | Required (prod) | Notes |
| --- | --- | --- |
| `AUTH_MODE` | yes (`oidc`) | `oidc` or `password` (development only) |
| `OIDC_ISSUER_URI` | yes | HTTPS OIDC issuer; discovery is resolved from it |
| `OIDC_CLIENT_ID` | yes | Registered client |
| `OIDC_CLIENT_SECRET` | provider-dependent | Confidential clients only; never logged |
| `OIDC_REDIRECT_URI` | yes | Must match provider registration, e.g. `https://<host>/api/auth/callback` |
| `OIDC_SCOPES` | no | Defaults to `openid profile email` |
| `JWT_AUDIENCE` | yes | Shared API audience, e.g. `grocery-api` |
| `JWT_JWKS_URI` | no | Overrides discovery `jwks_uri` for access-token checks |
| `PUBLIC_ORIGIN` | no | Public storefront origin for safe redirects |
| `REDIS_URL` | yes | Session and one-time authorization-request store |

`GET /api/auth/login` redirects to the provider; `GET /api/auth/callback` completes the
exchange and sets an opaque HttpOnly session cookie. The password `POST /api/auth/login`
route is unavailable while `AUTH_MODE=oidc`. Unsafe production identity configuration
fails at startup before the BFF serves traffic.

## Demo identity (dev only)

With `security.jwt.demo-enabled=true` and the `dev` profile active, each service starts an ephemeral RSA-2048 keypair and serves:

- `GET /.well-known/openid-configuration`
- `GET /.well-known/jwks.json`
- `POST /auth/login` (credentials `user` / `password`) returning a signed RS256 access token

The token carries `sub=customer-f7b1b25c` and all scopes, so the whole local stack works end to end without an external IdP. Never enable demo auth in `prod`.

## Current Gaps

- Dev profiles still use `ddl-auto=update`, which is convenient but should not be used for production schema management.
- Real migrations are absent. Add Flyway or Liquibase before relying on PostgreSQL in production.
- JWT configuration is fail-fast in production. The `prod` profile requires `JWT_ISSUER_URI` and `JWT_AUDIENCE`; tests use generated asymmetric keys instead of committed signing material, and `docker` falls back to the embedded demo issuer.
- Docker Compose previously used the `test` profile. It now uses `docker`, so services connect to the PostgreSQL containers and keep JWT validation enabled; with no `JWT_ISSUER_URI` supplied each service runs its embedded demo identity provider.
- Docker Compose intentionally avoids committed secret values and env-file dependencies. Use the `prod` profile, not `docker`, for any environment that needs real authentication or persistent data.
