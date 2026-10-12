# Phase 1 Data Model: Production Identity Setup

The feature introduces no new authoritative grocery domain data. It adds transient
identity-flow state alongside the existing session state.

## Authorization Request (transient)

Represents one in-flight sign-in attempt. Stored in Redis with a short TTL and consumed
exactly once.

| Field | Type | Rules |
| --- | --- | --- |
| `state` | string | Opaque, cryptographically random, unique; storage key |
| `nonce` | string | Random; must match the ID token `nonce` claim |
| `codeVerifier` | string | PKCE verifier; hashed to `code_challenge` (S256) |
| `returnTo` | string | Internal safe path; defaults to the storefront default |
| `expiresAt` | number | Epoch milliseconds; short-lived (e.g. 10 minutes) |

**Invariants**:
- Consumed (deleted) on first callback, success or failure, preventing replay.
- A callback whose `state` is unknown or expired is rejected.

## Session (existing, unchanged shape)

Represents a signed-in shopper's server-side state. The browser holds only `id` via cookie.

| Field | Type | Rules |
| --- | --- | --- |
| `id` | string | Cryptographically random; cookie value only |
| `jwt` | string | Provider access token; never returned to browser scripts |
| `userId` | string | Derived from the verified `sub` claim |
| `email` | string | `email` → `preferred_username` → `sub` fallback |
| `expiresAt` | number | Not later than the access token `exp` |

## Identity Provider Configuration (environment)

| Setting | Required (prod) | Notes |
| --- | --- | --- |
| `AUTH_MODE` | yes (`oidc`) | `oidc` or `password` (development only) |
| `OIDC_ISSUER_URI` | yes | Must be HTTPS in production |
| `OIDC_CLIENT_ID` | yes | Registered client |
| `OIDC_CLIENT_SECRET` | provider-dependent | Confidential clients only; never logged |
| `OIDC_REDIRECT_URI` | yes | Must match provider registration |
| `OIDC_SCOPES` | no | Default `openid profile email` |
| `JWT_AUDIENCE` | yes | Shared API audience, e.g. `grocery-api` |
| `PUBLIC_ORIGIN` | yes | Used for default redirect/logout destinations |

**Validation rules (fail-fast, production)**:
- `AUTH_MODE` must be `oidc`.
- `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_REDIRECT_URI` are present and absolute URLs;
  the issuer is HTTPS.
- `JWT_AUDIENCE` is non-blank.
- Errors never include secret values.
