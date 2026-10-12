# Phase 0 Research: Production Identity Setup

## Decision 1: Authorization Code flow with PKCE in the BFF

**Decision**: The BFF (Node/Express) performs the OIDC authorization code flow with PKCE.
The browser is redirected to the provider; the BFF handles the callback, exchanges the code
server-to-server, and owns the resulting session.

**Rationale**: The existing architecture already keeps tokens server-side and gives the
browser only an opaque cookie. A public SPA using the code flow without PKCE would be
vulnerable to code interception; PKCE binds the authorization request to the exchange.
Keeping the exchange server-side means the access token never reaches browser JavaScript,
preserving the current security boundary.

**Alternatives considered**: SPA-side code flow with tokens in memory (rejected: exposes
reusable tokens to scripts and diverges from the existing BFF boundary); implicit flow
(rejected: deprecated and token-leaking); password grant (rejected: production password
login is explicitly out of scope and discouraged by OAuth 2.1).

## Decision 2: Provider-neutral discovery and configuration

**Decision**: Discover `authorization_endpoint`, `token_endpoint`, and `jwks_uri` from
`{issuer}/.well-known/openid-configuration`. Configure client id, optional client secret,
redirect URI, and scopes via environment variables.

**Rationale**: No vendor SDK means any OIDC-conformant provider (Keycloak, Cognito, Auth0,
Okta) works by changing configuration only, satisfying E01's "select provider" decision
without code changes.

**Alternatives considered**: A vendor-specific SDK (rejected: lock-in); hardcoded provider
URLs (rejected: not deployable across environments).

## Decision 3: One-time authorization request store

**Decision**: Store state, nonce, and the PKCE code verifier keyed by a random `state`
value in Redis with a short TTL (e.g. 10 minutes), and delete on first use.

**Rationale**: Prevents CSRF and replay, keeps memory out of a single process so the BFF
can run as multiple concurrent Vercel function invocations, and matches the existing Redis
session store.

**Alternatives considered**: Signed stateless state cookies (viable but complicates
one-time semantics and PKCE verifier storage); in-memory map (rejected: not
multi-instance safe).

## Decision 4: Token verification

**Decision**: Verify the ID token with `issuer`, `clientId` audience, `exp`, and `nonce`
using the provider JWKS and `RS256`; verify the access token separately with the API
audience from `JWT_AUDIENCE`. Derive the session's stable subject from `sub` and display
identity from `email` (fallback `preferred_username`, then `sub`).

**Rationale**: The ID token authenticates the browser session to the client; the access
token authorizes upstream gateway calls. Distinguishing audiences avoids accepting an ID
token as an API token or vice versa.

**Alternatives considered**: Verifying only the access token (rejected: loses nonce
binding); verifying only the ID token (rejected: unsuitable as an API credential).

## Decision 5: Disabling the development mechanism in production

**Decision**: Introduce an explicit `AUTH_MODE` (`oidc` | `password`). Production requires
`oidc`; the password/demo login route returns `404` when the mode is `oidc`. The Spring
services and gateway gain a production-profile guard that fails startup if the demo
identity is enabled, the audience is blank, or the issuer is missing/not HTTPS.

**Rationale**: Active refusal is more robust than relying on operators to omit a flag.
Fail-fast matches the existing `${JWT_ISSUER_URI}` placeholder behaviour and extends it to
catch accidental demo enablement.

**Alternatives considered**: Relying on profile selection alone (rejected: a stray env var
could enable demo auth); warning instead of failing (rejected: production must not serve
weak auth).

## Decision 6: Safe post-sign-in redirect

**Decision**: Accept only relative internal paths (`/...` but not `//...`) as the return
destination; otherwise use a default page.

**Rationale**: Prevents open-redirect abuse while preserving "return to the requested page".

**Alternatives considered**: Allow-list of absolute origins (unnecessary complexity for a
same-origin storefront); allowing all absolute URLs (rejected: open redirect).
