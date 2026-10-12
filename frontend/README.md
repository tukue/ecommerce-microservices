# Grove Grocery frontend

React 19 + TypeScript + Vite storefront with an Express BFF. The customer flow
covers public catalogue/search, product details, sign-in, persistent cart,
retry-safe checkout, persisted confirmation, order history, and eventual receipts.
Production runs the shared BFF in Vercel Node functions and forwards requests
through Spring Gateway to private services. See
[the deployment guide](../docs/frontend-vercel-deployment.md).

Use [the design system](docs/design-system.md) for visual and interaction
consistency and [the architecture guide](docs/architecture.md) for code boundaries.

See [the current implementation](docs/current-implementation.md) for routes,
state management, checkout, receipts, and current limitations.

## Local development

Use Node 24 and install the locked dependencies with `npm ci`. Java 25, Maven,
and the backend services are required for real application requests. Each
service must trust the same demo issuer during local development.

For a native Spring `dev` setup, start each backend in a separate terminal from
repository root. Explicit ports avoid the differing defaults in dev profiles:

```sh
export CORS_ALLOWED_ORIGINS=http://localhost:5173
export DEMO_IDENTITY_BASE_URL=http://localhost:8081
export CART_SERVICE_BASE_URL=http://localhost:8081
export PRODUCT_SERVICE_BASE_URL=http://localhost:8083
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092

mvn -pl microservices/cart-service spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=8081
mvn -pl microservices/product-service spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=8083
mvn -pl microservices/order-service spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=8082
mvn -pl microservices/ledger-service spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=8084
```

Kafka must be available to both native services at the configured bootstrap
address **and advertised broker address**. The existing Compose Kafka broker
advertises its internal `kafka:9092` address and has no published host port;
it cannot be used unchanged for native clients. Use a broker configured for
host access, or run all services inside the existing Compose network following
[the Kafka integration guide](../docs/kafka-integration.md). Do not mix native
service URLs with container-only hostnames.

Start the existing Redis service with
`docker compose -f microservices/docker-compose.yml up -d redis`. From the
`frontend` directory, create a local ignored `.env` file with the appropriate
service addresses (these defaults are for the native ports above):

```dotenv
BFF_PORT=3000
CART_SERVICE_URL=http://localhost:8081
ORDER_SERVICE_URL=http://localhost:8082
PRODUCT_SERVICE_URL=http://localhost:8083
LEDGER_SERVICE_URL=http://localhost:8084
JWT_ISSUER_URI=http://localhost:8081
JWT_JWKS_URI=http://localhost:8081/.well-known/jwks.json
JWT_AUDIENCE=grocery-api
REDIS_URL=redis://localhost:6379
NODE_ENV=development
```

Run `npm run dev` to start Vite and the BFF. The dev-only backend identity
provider accepts `user` / `password`. Runtime identity and Redis credentials
belong in deployment secret configuration, never source files.

The browser talks only to relative `/api` URLs. Vite forwards these to the BFF.
Redis is mandatory for runtime session storage. Authentication tokens remain
in that server-side store and are never returned to browser JavaScript.

The BFF wraps receipt results as `{ status: "ready", content: "..." }` or
returns HTTP 202 with `{ status: "pending" }`. A pending receipt is returned
only after the order service confirms that the current customer owns the order.
The UI polls for up to 30 seconds, then offers an explicit retry. An unavailable
receipt does not invalidate a saved order or trigger a new checkout.

## Verification

```sh
npm run format
npm run lint
npm run type-check
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

For a preinstalled Chromium, set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to its
executable path instead of downloading another browser. Playwright starts Vite
and uses stateful BFF API fixtures. These tests verify the browser customer
journey; they do not establish that a deployed backend or Kafka is healthy.
Run the backend integration suite and the separate `npm run test:e2e:live`
staging gate before claiming deployment readiness. The live suite starts no
mock server: set HTTPS `LIVE_BASE_URL` and securely supplied Playwright
`LIVE_SESSION_A` / `LIVE_SESSION_B` storage-state paths for two dedicated
identities. Writes require `LIVE_ALLOW_CHECKOUT=1`, seeded `LIVE_PRODUCT_ID`,
and an empty existing cart; only run them on disposable staging accounts.
See the deployment guide for CI secret setup and outstanding release gates.

The current cart/order contracts do not contain currency or checkout payment,
address, or delivery data. Cart amounts are previews from server line prices;
checkout submits only cart ID and an idempotency key, and the persisted order
supplies its authoritative total. Product prices show the currency supplied
by the catalogue. Payment and delivery screens require backend contracts first.
