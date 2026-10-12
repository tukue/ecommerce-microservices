# Predeployment implementation brief

## Feature definition

**Problem:** A static-only storefront deployment cannot serve secure customer
sessions or reach private backend APIs. Fixture browser tests cannot prove the
real identity, Gateway and Kafka integration works.

**Outcome:** Deploy the Vite storefront and shared Express BFF together on
Vercel. The browser uses same-origin `/api`; the BFF stores opaque sessions in
TLS Redis, completes real OIDC authorization code with PKCE, and forwards
bearer tokens only through Spring Gateway to private services. Checkout retains
its idempotency key and persisted confirmation while receipt projection catches
up asynchronously.

**Scope:** Production packaging/routing, validated server configuration,
production OIDC/session protections, explicit Gateway routing, Redis integration
checks, separate real staging release gate, deployment documentation. Payment,
delivery and refresh-token flows require separate contracts and are outside
this release. Token expiry requires reauthentication.

## Acceptance and evidence

| Acceptance                                                                           | Evidence and current limit                                                                                            |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| API routing survives Vercel build; SPA deep links work                               | Checked-in function entrypoint and Vercel routing; real hosted deploy still required                                  |
| Production uses real OIDC, HttpOnly opaque sessions and TLS Redis                    | Configuration/auth implementation and automated security checks; actual provider registration and sign-in pending     |
| BFF uses Gateway, private services stay private                                      | Explicit Gateway topology and route checks; live Render routing and JWT validation pending                            |
| Session state persists across function instances                                     | Real Redis integration gate in frontend CI; managed hosted Redis verification pending                                 |
| Catalogue, authentication, owned history and confirmation work against real services | Separate `test:e2e:live` suite; not executed remotely without endpoints and two dedicated sessions                    |
| Checkout replay creates one order, foreign access fails, receipt becomes ready       | Opt-in live write journey requires dedicated seeded product/accounts and an empty cart; Kafka/ledger evidence pending |
| Deployment docs describe the implementation consistently                             | Updated combined Render/Vercel rollout and frontend guide                                                             |

## Local validation (2026-10-09)

Frontend formatting, lint, complete client/server type checking and production
build pass. All 246 frontend/BFF tests across 58 files pass, including five
integration tests against real Redis. The frontend dependency audit reports
zero vulnerabilities. Nine
fixture browser journeys pass; Gateway passes 18 tests and packages on Java 25.
The Node 24 production Docker image builds and its health, API-root and unknown
API responses return JSON. Readiness returns 503 when Gateway is unavailable.

Hosted Vercel routing, actual provider sign-in and the three live staging
journeys still require configured infrastructure and dedicated identities.
These local checks do not establish production readiness.

## Ownership and unresolved requirements

Implementation assignments, dependencies and commit scope are tracked in
[the task breakdown](predeployment-task-breakdown.md). The platform/auth owner
must register the real provider, client, API audience and callbacks. The hosting
owner must provision Render services/databases, Vercel environments and TLS
Redis. The backend owner must supply Gateway and Kafka/receipt validation.
The QA/release owner must supply two short-lived real staging sessions securely,
seed disposable accounts/product, and execute the opt-in write gate. The release
owner approves production rollout after evidence from those gates is recorded.

Credentials and session cookies belong in environment secret stores, never
Git, chat, logs or test artifacts. No live endpoint or account is invented to
make a release gate appear green. Preview and production environments require
separate backend destinations and Redis session namespaces.
