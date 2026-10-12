# Vercel storefront deployment

The active storefront is React + Vite. The production entrypoint is the Vercel
Node function `frontend/api/[...path].ts`, sharing the Express BFF with local
development. Static assets come from `dist`. `frontend/vercel.json` routes
`/api` before the SPA fallback so product and confirmation deep links work.

Browser requests remain relative `/api` calls. The BFF resolves opaque HttpOnly
sessions in Redis, forwards bearer tokens to Spring Gateway, and Gateway routes
to private services. Tokens, provider credentials and upstream addresses never
belong in browser `VITE_*` variables.

## Project configuration

Import the GitHub repository with Root Directory `frontend`, framework `Vite`,
install command `npm ci`, build command `npm run build`, output directory `dist`,
and the Node version in `frontend/.nvmrc`. Keep production and staging separate.
Set production variables in Vercel settings:

| Variable                                         | Requirement                                                       |
| ------------------------------------------------ | ----------------------------------------------------------------- |
| `NODE_ENV`                                       | `production`                                                      |
| `PUBLIC_ORIGIN`                                  | Exact canonical HTTPS frontend origin                             |
| `GATEWAY_URL`                                    | Reachable HTTPS Spring Gateway origin                             |
| `AUTH_MODE`                                      | `oidc`; production rejects demo sign-in                           |
| `JWT_ISSUER_URI`, `JWT_JWKS_URI`, `JWT_AUDIENCE` | Real provider issuer, signing keys and API audience               |
| `OIDC_CLIENT_ID`                                 | Registered web client                                             |
| `OIDC_CLIENT_SECRET`                             | Secret if the provider requires a confidential client             |
| `OIDC_REDIRECT_URI`                              | Exactly `PUBLIC_ORIGIN/api/auth/callback`; register with provider |
| `REDIS_URL`                                      | TLS `rediss://` connection supplied securely                      |
| `SESSION_NAMESPACE`                              | Distinct production/staging namespace                             |

Check `frontend/server/config.ts` for the validated runtime contract. Sessions
expire with the provider token; the current rollout requires reauthentication
rather than refresh tokens. Register staging callbacks separately; preview
origins must use staging infrastructure and never silently call production.

## Release evidence

Required local gates: formatting, lint, type-check, unit tests, Redis integration,
production build, and fixture browser tests. Fixture tests validate UI behavior;
they cannot establish Gateway, identity provider, Kafka or deployment health.

Use `npm run test:e2e:live` for the separate real staging suite. It starts no
server and installs no API mocks. Provide `LIVE_BASE_URL` (HTTPS; local loopback
HTTP only for local integration) and `LIVE_SESSION_A` / `LIVE_SESSION_B` paths
to short-lived Playwright storage-state JSON files for two dedicated test users.
Create those sessions through real OIDC sign-in; securely export browser context
storage state. Never commit session files, print their cookies, or upload traces
containing them. Expired sessions fail the gate and must be renewed.

Public catalogue/detail/deep links, signed-out access and authenticated history
run without writes. Set `LIVE_ALLOW_CHECKOUT=1` and `LIVE_PRODUCT_ID` only on a
staging stack with seeded disposable accounts and stock. The write journey
refuses a nonempty existing cart, places one order, replays the same idempotency
key, verifies one added order, checks persisted confirmation, polls receipt
readiness for at most 60 seconds, and denies the other user's order/receipt
access. Receipt projection may already be ready by its first read; a real 202
is checked as pending when observed. No mock pending state is manufactured.

The `Frontend CI` manual dispatch uses the protected GitHub `staging` environment
and requires its `LIVE_BASE_URL` variable to match the requested canonical HTTPS
origin. Its `LIVE_SESSION_A_JSON` / `LIVE_SESSION_B_JSON` secrets are materialized as
mode-0600 runner files and removed afterward. Configure environment reviewers
and scoped, short-lived sessions before enabling it. No live secrets or traces
are uploaded as CI artifacts. A read-only dispatch alone is not checkout release
sign-off; record a successful opt-in write run as well.

See [the combined rollout](DEPLOYMENT_RENDER_VERCEL.md) and
[implementation evidence](predeployment-implementation.md). Real provider,
Gateway, Redis hosting and staging accounts remain external release gates.
