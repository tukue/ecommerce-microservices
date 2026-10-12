# Tasks: Production Identity Setup

**Feature**: `002-production-identity-setup` | **Branch**: `feature/production-identity-setup`

**Input**: Design documents from `/specs/002-production-identity-setup/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

## Phase 1: Setup

- [X] T001 Create feature branch `feature/production-identity-setup` from `origin/main`
- [X] T002 Write Spec Kit artifacts (spec, plan, research, data-model, contracts, quickstart, checklist)
- [X] T003 Persist feature directory in `.specify/feature.json`

## Phase 2: Foundational (BFF identity infrastructure)

- [X] T004 Add provider-neutral OIDC settings (`AUTH_MODE`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REDIRECT_URI`, `OIDC_SCOPES`, `PUBLIC_ORIGIN`) plus fail-fast production validation in `frontend/server/config.ts`
- [X] T005 Add one-time authorization-request store (Redis + in-memory test adapter) in `frontend/server/auth-request-store.ts`
- [X] T006 Add OIDC client (discovery cache, authorization URL with S256 PKCE, code exchange, ID/access token verification) in `frontend/server/oidc.ts`

## Phase 3: User Story 1 - Provider sign-in (P1)

- [X] T007 [US1] Implement `GET /api/auth/login` initiation (redirect to provider, store state/nonce/PKCE, safe `returnTo`) in `frontend/server/bff.ts`
- [X] T008 [US1] Implement `GET /api/auth/callback` (one-time state consumption, code exchange, token verification, session cookie, safe redirect) in `frontend/server/bff.ts`
- [X] T009 [US1] Add contract tests for initiation, callback success, and safe redirect in `frontend/server/__tests__/oidc-auth-contract.test.ts`

## Phase 4: User Story 2 - Safe sessions (P2)

- [X] T010 [US2] Enforce one-time state consumption and reject replay/tampered/expired responses in `frontend/server/bff.ts`
- [X] T011 [US2] Rotate the cookie on sign-in and delete the session on logout/expiry in `frontend/server/bff.ts`
- [X] T012 [US2] Add tests for state mismatch, replay rejection, and 401 on expired session in `frontend/server/__tests__/oidc-auth-contract.test.ts`

## Phase 5: User Story 3 - Unsafe configuration rejected (P2)

- [X] T013 [US3] Add `GET /api/auth/config` and make password login unavailable in OIDC mode in `frontend/server/bff.ts`
- [X] T014 [US3] Add `application-prod.properties` for the gateway in `microservices/gateway-service/src/main/resources/`
- [X] T015 [US3] Add fail-fast `ProductionIdentityGuard` to gateway and each service's `config/` package
- [X] T016 [US3] Add config-validation tests for the BFF (`frontend/server/__tests__/config.test.ts`)

## Phase 6: Frontend sign-in entry point

- [X] T017 Add `getAuthMode()` and `beginLogin()` to `frontend/src/features/auth/api/auth-api.ts`; expose mode and redirect login through `auth-context.tsx`
- [X] T018 Render the provider sign-in entry point in `frontend/src/features/auth/components/login-page.tsx` while keeping the development form for password mode

## Phase 7: Polish

- [X] T019 Run `npm run test`, `npm run type-check`, `npm run lint`, `npm run format` in `frontend/`
  - `npx vitest run`: 222-223 pass; one pre-existing timing-sensitive debounce test (`product-journey`) is flaky under full-suite load and passes in isolation. `lint` clean, `format` clean, `type-check` clean (note: `tsconfig.app.json` still omits `server/` and `src/features/auth`; verified separately).
- [X] T020 Run Maven tests for gateway and one service
  - Provisioned Temurin JDK 25 in `/tmp/opencode` and ran `./mvnw -pl microservices/gateway-service,microservices/cart-service -am test`.
  - `cart-service`: **SUCCESS** (includes `ProductionIdentityGuardTest`).
  - `gateway-service`: new `ProductionIdentityGuardTest` **passes** (3/3); `ProductionIdentityGuard` is `@Profile("prod")` so it is inert under tests.
  - Pre-existing (not E01): `GatewayRoutesTest` fails to load its context with `NoClassDefFoundError: org/springframework/boot/autoconfigure/web/ServerProperties`, thrown from `spring-cloud-commons` 4.3.2 `SimpleDiscoveryClientAutoConfiguration`. Boot 4.1.1 relocated/removed that class, so Spring Cloud 2025.0.2 is incompatible with the pinned Boot version. Fails on `main` too; flagged separately.
- [X] T021 Document the production identity environment contract in `docs/configuration-guide.md`
