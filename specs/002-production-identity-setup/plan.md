# Implementation Plan: Production Identity Setup

**Branch**: `feature/production-identity-setup` | **Date**: 2026-10-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-production-identity-setup/spec.md`

## Summary

Replace the development-only credential login with a provider-neutral OIDC authorization
code + PKCE flow owned by the Node BFF, and harden production configuration so the
development identity mechanism cannot run in production. The BFF gains a one-time
authorization-request store (state + nonce + PKCE verifier) backed by Redis, OIDC
discovery, token exchange, and ID/access token verification; the browser keeps receiving
only an opaque HttpOnly session cookie. The gateway gains a production profile and the
Spring services/gateway gain a fail-fast production identity guard.

## Technical Context

**Language/Version**: TypeScript 5.8 (Node 24) for the BFF; Java 21 / Spring Boot 3.2.5 for services

**Primary Dependencies**: Express 5, jose 6 (JWKS/JWT/JWT verification), redis 6, zod 4, vitest 3, supertest; Spring Security OAuth2 Resource Server, Spring Cloud Gateway

**Storage**: Redis for sessions and one-time authorization requests; PostgreSQL for domain data (unchanged)

**Testing**: vitest (server + React), supertest contract tests, Maven/JUnit for Java

**Target Platform**: Linux containers (Render backend/gateway), Vercel Node function for the BFF

**Project Type**: Web application (React/Vite frontend + Express BFF + Spring microservices)

**Constraints**: No reusable token in the browser; provider-neutral (no vendor SDK); fail-fast on unsafe production identity config; no secret values in logs

**Scale/Scope**: Single BFF auth path, four services plus gateway, one edge

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The project constitution is an unfilled template; no ratified principles exist to gate on.
The change upholds the repository's established invariants: server-authoritative identity,
tokens never exposed to browser scripts, fail-fast production configuration, and tests for
security behaviour.

## Project Structure

### Documentation (this feature)

```text
specs/002-production-identity-setup/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (BFF auth HTTP contract)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
frontend/
├── server/
│   ├── bff.ts                 # Express BFF: login/callback/me/logout + proxy
│   ├── config.ts              # env schema + fail-fast validation
│   ├── oidc.ts                # NEW: discovery, authorization URL, code exchange, verification
│   ├── auth-request-store.ts  # NEW: one-time authorization-request store (Redis + memory)
│   ├── session-store.ts       # opaque session store (unchanged shape)
│   ├── proxy.ts               # route table (unchanged)
│   └── __tests__/             # contract tests
└── src/features/auth/         # sign-in entry point (redirect in OIDC mode)

microservices/
├── gateway-service/src/main/resources/application-prod.properties   # NEW
├── gateway-service/.../config/ProductionIdentityGuard.java           # NEW
└── {cart,order,product,ledger}-service/.../config/ProductionIdentityGuard.java  # NEW
```

**Structure Decision**: Keep the existing web-application layout. Provider-neutral OIDC
lives in the BFF (`frontend/server/`) because the BFF already owns the session boundary;
services and the gateway only validate bearer tokens. Configuration hardening is added to
the existing per-service `config/` packages and gateway resources.

## Complexity Tracking

> No constitution violations. No additional projects or abstractions beyond two small BFF
> modules and one guard class per service.
