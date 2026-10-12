# Quickstart: Production Identity Setup

## 1. Configure the identity provider (E01, identity owner)

Register an OIDC client with the chosen provider and record:

- Issuer URL, e.g. `https://id.example.com/realms/grocery`
- Client ID (and client secret if the client is confidential)
- Redirect URI: `https://<storefront-host>/api/auth/callback`
- Scopes: `openid profile email`
- The shared API audience your services expect, e.g. `grocery-api`

## 2. Configure the BFF (Vercel / local)

```bash
AUTH_MODE=oidc
OIDC_ISSUER_URI=https://id.example.com/realms/grocery
OIDC_CLIENT_ID=grocery-storefront
OIDC_CLIENT_SECRET=<secret>            # only for confidential clients
OIDC_REDIRECT_URI=https://storefront.example.com/api/auth/callback
OIDC_SCOPES="openid profile email"
JWT_JWKS_URI=https://id.example.com/realms/grocery/protocol/openid-connect/certs
JWT_AUDIENCE=grocery-api
PUBLIC_ORIGIN=https://storefront.example.com
REDIS_URL=rediss://<redis>
NODE_ENV=production
```

Discovery, endpoints, and `jwks_uri` are resolved from `OIDC_ISSUER_URI` when
`JWT_JWKS_URI` is omitted. In `NODE_ENV=production`, missing or non-HTTPS identity
settings fail startup.

## 3. Configure the services and gateway

`prod` profile (already present for services; added for the gateway) requires:

```bash
JWT_ISSUER_URI=https://id.example.com/realms/grocery
JWT_AUDIENCE=grocery-api
security.jwt.demo-enabled=false   # enabling it in prod fails startup
```

## 4. Verify locally

```bash
cd frontend
npm run test        # unit + BFF contract tests
npm run type-check
npm run lint
```

Java verification (per service):

```bash
./mvnw -q -pl microservices/gateway-service test
```

## 5. Manual sign-in check

1. Start Redis and the BFF (`npm run dev`).
2. Open a protected page while signed out.
3. Confirm the browser is redirected to the provider, not shown a password form.
4. Complete sign-in and confirm you return to the requested page.
5. Confirm DevTools shows only the `grocery_session` cookie and no reusable token.
