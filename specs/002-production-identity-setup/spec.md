# Feature Specification: Production Identity Setup

**Feature Branch**: `002-production-identity-setup`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description: "production identity setup (E01)"

## Overview

Today the storefront signs in against an embedded, development-only identity capability
that mints tokens from a hardcoded username and password. That mechanism must never be
used in production. This feature replaces it with a provider-neutral production identity
path: shoppers authenticate with an external OpenID Connect (OIDC) identity provider, the
server-side session boundary keeps reusable tokens out of the browser, and every deployed
service refuses to start with unsafe identity configuration.

E01 is the identity-owner decision recorded in
`docs/predeployment-task-breakdown.md`: select an OIDC provider and configure issuer/JWKS,
the grocery API audience, client registration, redirect URI, and required identity claims.
This specification turns that decision into durable, provider-neutral behaviour so any
conforming OIDC provider (Keycloak, Cognito, Auth0, Okta, etc.) can be dropped in without
code changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Sign in through the external identity provider (Priority: P1)

An unauthenticated shopper who opens a protected page is sent to the organization's
identity provider to sign in. After authenticating there, the shopper returns to the page
they originally requested and can shop normally. No password is ever entered or handled by
the storefront itself.

**Why this priority**: Without a working production sign-in, no authenticated journey can
be offered in production. This is the core of E01.

**Independent Test**: Configure a test OIDC provider (or a stubbed discovery/token
endpoint), request a protected page while signed out, complete the provider sign-in, and
confirm the shopper lands back on the originally requested page with an active session.

**Acceptance Scenarios**:

1. **Given** a signed-out shopper opens a protected page, **When** sign-in begins, **Then**
   the shopper is redirected to the configured identity provider with the correct client,
   redirect destination, scopes, and an unguessable request identity.
2. **Given** the identity provider returns the shopper to the application, **When** the
   response is processed, **Then** the shopper is returned to the page they originally
   requested only if that destination is an internal, safe path.
3. **Given** a successful provider sign-in, **When** the session is established, **Then**
   the browser holds only an opaque session cookie and never receives the reusable access
   or identity token.
4. **Given** the identity provider reports a failure or the shopper cancels, **When** the
   application handles the response, **Then** the shopper is shown a clear, non-technical
   outcome and no session is created.

---

### User Story 2 - Sessions remain safe and short-lived (Priority: P2)

An authenticated shopper's session is tied to the provider-issued token's validity, is
rotated on sign-in, can be ended explicitly, and is rejected once expired. Forged or
replayed sign-in responses are refused.

**Why this priority**: A correct sign-in flow is only safe if the resulting session cannot
be forged, replayed, or outlived.

**Independent Test**: Attempt a sign-in response replay, a tampered request identity, an
expired token, and an explicit sign-out, and confirm each is rejected or cleared while a
valid session continues to work.

**Acceptance Scenarios**:

1. **Given** a sign-in response has already been processed, **When** the same response is
   replayed, **Then** it is rejected and no new session is created.
2. **Given** a sign-in response carries a request identity the application did not issue,
   **When** it is processed, **Then** it is rejected.
3. **Given** an access token has expired or fails signature/issuer/audience validation,
   **When** a protected request is made, **Then** the shopper is treated as signed out.
4. **Given** an authenticated shopper selects sign out, **When** the action completes,
   **Then** the server-side session is deleted and the browser cookie is cleared.

---

### User Story 3 - Unsafe identity configuration is rejected (Priority: P2)

A deployer cannot accidentally run production with the development identity mechanism,
missing issuer/audience, or a non-encrypted issuer. Misconfiguration fails at startup with
a safe message rather than silently allowing weak authentication.

**Why this priority**: Production identity is only trustworthy if the platform refuses to
start in a weakened state. This is the config-hardening half of E01.

**Independent Test**: Start a production-profiled service or the edge with the demo
identity enabled, an empty audience, or a non-HTTPS issuer, and confirm startup fails with
a clear error and without printing secret values.

**Acceptance Scenarios**:

1. **Given** the production profile, **When** the development identity mechanism is
   enabled by configuration, **Then** startup fails and the failure is logged as a
   configuration error.
2. **Given** the production profile, **When** the issuer or audience is missing or a
   non-HTTPS issuer is supplied, **Then** startup fails before serving traffic.
3. **Given** any configuration failure, **When** the error is reported, **Then** no secret
   or token value appears in the output.

---

### Edge Cases

- The identity provider discovery document is temporarily unreachable: sign-in fails with
  a clear, retryable outcome and existing sessions continue to work.
- The provider returns an identity without an email claim: the session still establishes
  using the stable subject identifier, and display falls back gracefully.
- Concurrent tab sign-ins: each request gets its own one-time request identity so one tab
  cannot consume another tab's response.
- Provider token lifetime is shorter than the browser's expected session: the session
  expires with the token and the shopper is asked to sign in again.
- The originally requested page is an external URL or an open redirect attempt: the
  destination is rejected and the shopper is sent to a safe default page.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST authenticate production shoppers through an external OIDC
  identity provider using the authorization code flow with PKCE; no production password
  login is offered.
- **FR-002**: The system MUST be provider-neutral: provider endpoints are discovered from
  a configured issuer, and client identifier, redirect destination, and requested scopes
  are configuration, not hardcoded values.
- **FR-003**: The system MUST send each sign-in request a single-use request identity,
  a nonce, and a proof key, and MUST reject responses that do not match all three.
- **FR-004**: The system MUST verify the returned identity and access tokens for signature,
  issuer, audience, and expiry before creating a session.
- **FR-005**: The system MUST never expose reusable tokens to browser scripts; the browser
  receives only an opaque session cookie.
- **FR-006**: The system MUST establish an HTTP-only, SameSite session cookie that is
  Secure in production, and MUST rotate/replace the cookie on each successful sign-in.
- **FR-007**: The system MUST return the shopper to the originally requested internal path
  only when that path is safe, otherwise to a default page.
- **FR-008**: The system MUST expire sessions no later than the provider token's validity
  and MUST delete the session on explicit sign out.
- **FR-009**: The system MUST expose the active authentication mode (for example, external
  identity versus development) so the storefront renders the correct sign-in entry point.
- **FR-010**: The system MUST prevent the development identity mechanism and password
  login from operating when production identity is active.
- **FR-011**: Production-profiled services and the edge MUST fail fast at startup when the
  development identity mechanism is enabled, the audience is blank, or the issuer is
  missing or not HTTPS.
- **FR-012**: Configuration and identity failures MUST NOT disclose secret values, tokens,
  or full claim contents in logs or responses.
- **FR-013**: The system MUST provide the edge gateway with a production configuration
  profile equivalent to the other services.
- **FR-014**: Automated tests MUST cover sign-in initiation, callback success, state/nonce
  mismatch rejection, replay rejection, token validation failure, config fail-fast, and
  sign out.

### Key Entities *(include if feature involves data)*

- **Authorization Request**: A single sign-in attempt. Attributes: single-use request
  identity, nonce, proof-key verifier, safe return destination, short expiry. Consumed
  exactly once.
- **Session**: A signed-in shopper's server-side state. Attributes: opaque session id,
  provider access token, stable subject identifier, display identity, expiry. Never
  returned in full to the browser.
- **Identity Provider Configuration**: Issuer, client identifier, client secret (when
  required), redirect destination, requested scopes, expected API audience, and the
  active authentication mode.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of protected pages require an authenticated session, and signed-out
  shoppers are guided to the identity provider rather than a password form.
- **SC-002**: 0 automated checks find a reusable token reachable from the browser.
- **SC-003**: 100% of replayed, tampered, or expired sign-in responses are rejected
  without creating a session.
- **SC-004**: 100% of production startup attempts with the development identity mechanism
  enabled or unsafe issuer/audience configuration fail before serving traffic.
- **SC-005**: A shopper can complete provider sign-in and return to the requested page in
  under 15 seconds on a healthy network.

## Assumptions

- E01's identity owner will select and configure a real OIDC provider; this feature does
  not choose a vendor and works with any OIDC-conformant provider.
- The external provider issues RS256-signed tokens with a stable `sub` claim, an email or
  preferred-username claim, and the shared `grocery-api` audience for access tokens.
- The server-side session store (Redis) is available in production and already used by the
  session boundary.
- The existing development identity mechanism remains available only in non-production
  profiles for local work.
- Registration, password recovery, multi-factor enrolment, refresh-token use, and social
  identity are out of scope; the provider owns the credential lifecycle.
