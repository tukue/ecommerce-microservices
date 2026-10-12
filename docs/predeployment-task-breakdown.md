# Predeployment implementation tasks

Date: 2026-10-09. Target: Render backend/Gateway and Vercel storefront.

This breakdown implements the rollout in [DEPLOYMENT_RENDER_VERCEL.md](DEPLOYMENT_RENDER_VERCEL.md), using the merged [frontend](feature-definition-before-vercel.md) and [backend](backend-feature-definition-before-deployment.md) feature-definition templates. The user authorized implementation, agent assignments, and committing/pushing this breakdown. Production deployment is a later step.

## Working architecture

The browser loads the Vite storefront and uses relative `/api` requests. The Vercel Node BFF keeps verified access tokens in Redis and injects bearer authorization into requests to Spring Gateway. Gateway validates tokens and forwards requests to private services. The browser receives an opaque HttpOnly session cookie.

Gateway's merged design also describes public-edge routing to the BFF. That must not bypass session resolution or expose JWTs to browser JavaScript. The deployment implementation must record the final compatible topology, prevent auth routing loops, and preserve receipt adaptation.

## Assignments and acceptance criteria

Tasks marked in progress are assigned work, not completed release gates.

| ID | Assigned agent / owner | Task | Dependencies | Acceptance criteria | Initial status |
| --- | --- | --- | --- | --- | --- |
| S01 | Primary agent | Validate production configuration and reject unsafe defaults. | None | Explicit HTTPS Gateway/issuer/JWKS/public origin, TLS Redis, isolated session namespace, and supported auth mode are required; configuration errors never expose values. | In progress |
| S02 | Primary agent | Harden browser sessions and state-changing requests. | S01 | Trusted-origin checks reject forged mutations; login throttling, session rotation, expiry/logout, strict `sub`/`exp` claims, no-store responses, and safe dependency errors have tests. | In progress |
| S03 | Primary agent; identity owner supplies configuration | Implement provider-neutral OIDC authorization code + PKCE. | S01, S02 | One-time Redis state, nonce, ID/access-token verification, safe return destination, and opaque-cookie sessions pass integration tests; production password/demo login is disabled. No specific provider is assumed. | In progress; provider selection pending |
| G01 | Gateway agent | Correct Gateway/BFF/service path contracts. | Merged Gateway implementation | Catalogue transformation happens once; customer prefixes remain intact; ledger receipt paths reach ledger; methods/query/body/idempotency and correlation headers survive forwarding. | In progress |
| G02 | Gateway agent | Test real Gateway security and validate Java build. | G01 | Valid JWT accepted; invalid issuer/audience/signature/claims rejected; delegated BFF auth routes work; Java 25 test/package commands pass without disabling compatibility or TLS verification. | In progress |
| D01 | Deployment agent | Package the BFF as a Vercel Node function. | S01–S03 | Concurrent invocations reuse initialization; failed initialization can recover; paths, status, cookies, and error responses are preserved. | In progress |
| D02 | Deployment agent | Configure Vercel routing and standalone BFF packaging. | D01, G01 | `/api` and unknown API paths never return SPA HTML; static assets and deep links work; Node 24 standalone/container startup and shutdown are documented and tested. | In progress |
| Q01 | Primary agent | Make frontend and BFF type checking complete. | None | All client/server/function sources are checked; stale include lists and incorrectly typed fixtures are corrected; active UI behavior remains verified. | In progress |
| Q02 | Primary agent + deployment agent | Exercise real Redis session behavior. | S02, S03 | Shared sessions survive instance reconnection, environment namespaces are isolated, transactions consume once atomically, expiry/logout and throttling work using actual Redis. | In progress |
| Q03 | Live/CI/documentation agent | Add a separate live staging journey suite. | G01, D01, configured staging | Two dedicated identity states verify customer ownership; catalogue/cart/checkout/replay/persisted confirmation/receipt are exercised without mocks. Writes require an explicit staging opt-in and an empty test cart. Missing configuration fails clearly. | In progress; remote execution pending |
| Q04 | Live/CI/documentation agent | Add CI quality and staging-validation gates. | Q01–Q03 | Frontend checks and Redis integration run; explicit staging workflow runs live checks with secure identity state; artifacts never publish session credentials or authenticated traces. | In progress |
| O01 | Live/CI/documentation agent | Update deployment instructions and fill the feature brief. | D02, Q04 | Instructions match Vite, Vercel BFF, Gateway, Redis and OIDC; rollout, rollback, verification, and unresolved gates are explicit. | In progress |
| R01 | Primary agent | Review combined changes, run required checks, commit and push implementation. | All local implementation tasks | Format/lint/types/unit/integration/build/browser evidence recorded; diff checked for secrets/unrelated edits; blockers reported; feature branch pushed. | Pending |

## Decisions and external work

| ID | Responsible role | Required decision/action | Unblocks |
| --- | --- | --- | --- |
| E01 | Identity owner | Select OIDC provider and configure issuer/JWKS, grocery API audience, client registration, redirect URI, and required identity claims. Enter secrets securely in provider settings. | Production sign-in validation |
| E02 | Platform/Gateway owner | Confirm final topology and deploy a reachable staging Gateway with private upstreams and trusted networking. | Live API integration |
| E03 | Platform owner | Provision encrypted/authenticated Redis and environment-specific session namespaces. | Production sessions |
| E04 | Platform/backend owner | Provision databases/migrations, managed Kafka/TLS/auth/topics, and real catalogue data. | Real checkout-to-receipt journey |
| E05 | Vercel/Render account owner | Connect deployment projects, supply configuration, configure domains/TLS/callbacks, and set preview isolation. | Hosted staging checks |
| E06 | Release/operations owner | Assign monitoring/alert response and approve tested rollback and release evidence. | Production promotion |

Payment, delivery, fulfilment, and refunds are outside this technical integration release. Existing checkout creates an order and does not establish payment success. A commercial launch requires separately approved contracts and acceptance tests for those capabilities.

## Execution order

1. Complete S01/S02 and Q01 while Gateway and deployment agents work on their independent files.
2. Complete S03 and validate S02/S03 with real Redis under Q02.
3. Join G01/G02 with D01/D02; preserve the browser session boundary and prevent routing loops.
4. Finish Q03/Q04/O01 and run local verification under R01.
5. Supply E01–E05; execute live staging checks against the deployed topology.
6. Complete E06 and make a separate production release decision.

## Handoff requirements

Each agent reports the files changed, commands executed, test counts/outcomes, and unresolved blockers. Agents do not independently commit, push, publish, or change files owned by another agent. The primary agent integrates and reviews their work and distinguishes local passing checks from unexecuted hosted deployment checks.
