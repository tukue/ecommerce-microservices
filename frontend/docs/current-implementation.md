# Current frontend implementation

This guide describes the active Vite storefront. Use the
[design system](design-system.md) for visual and language consistency and the
[architecture guide](architecture.md) for import boundaries. Legacy Next.js files
are not the active customer entrypoint.

Follow [the implementation guide](../../docs/implementation-guide.md) when adding
or changing features.

## Entry points and customer routes

`src/main.tsx` mounts authentication and cart providers around the application.
`src/routes.tsx` defines React Router routes and the shared storefront layout.
Private routes wait for session resolution before rendering customer data.

| Route | Access | Implemented behavior |
| --- | --- | --- |
| `/` | Public | Redirects to the product catalogue |
| `/products` | Public | Catalogue, URL-backed search, availability filter, sorting, recovery states |
| `/products/:id` | Public | Product details, stock-aware quantity selection, add-to-cart action |
| `/login` | Public | Sign-in and validated internal return destination |
| `/cart` | Session required | Read, refresh, add/update/remove lines, price preview |
| `/checkout` | Session required | Review and retry-safe order submission |
| `/confirmation/:orderId` | Session required | Persisted order details and eventual receipt |
| `/orders` | Session required | Owned order history and links to persisted details |

The shared layout provides navigation, cart count, account actions, footer, and
keyboard skip navigation. Customer-facing copy uses “cart” consistently and a
concise professional tone.

## API and session boundary

The browser uses relative `/api` requests. Vite proxies these to the Express BFF
in local development. `server/proxy.ts` allowlists methods and paths; UI
components do not call configured microservice URLs directly.

| Browser API | Owner |
| --- | --- |
| `/api/auth/login`, `/api/auth/logout`, `/api/auth/me` | BFF session handling |
| `/api/catalog/products`, search, product detail | Product-service; catalogue prefix stripped upstream |
| `/api/customer/cart` and cart-item mutations | Cart-service |
| `/api/customer/checkout` | Order-service |
| `/api/customer/orders` and order detail | Order-service |
| `/api/customer/ledger` and order receipt | Ledger-service, with BFF receipt adaptation |

The BFF stores verified upstream JWTs in Redis and returns only public identity
to the browser. Expired sessions trigger sign-in recovery. Returning to a tab revalidates the
shared cookie session and hides the cached identity while the check runs. This
detects sign-out or account changes in another tab. Failed checks clear the local
identity; a later focus can retry. Provider tokens are not refreshed. Feature API adapters
validate response shapes before mapping DTOs into domain data.

## Cart state and concurrent requests

The cart provider tracks session ownership and refresh request IDs. Responses
from an older refresh or an earlier session cannot replace current customer data.
A shared mutation lock serializes cart changes. Pending item IDs control each
line's disabled state; checkout is disabled while a mutation is pending.

Successful mutations use the server response. Optimistic failures restore or
reload authoritative data and display actionable feedback. A session change
invalidates the previous customer's state. The header count derives from the
current cart quantities.

Cart totals are numeric previews of line prices. The current cart contract lacks
an aggregate total and currency; the UI does not invent currency information.
The order service calculates and records the checkout total.

## Checkout, confirmation, and receipts

The frontend retains an idempotency key for the current cart attempt, validates
stored retry state, and guards duplicate submission. An ambiguous failure keeps
the same key for retry. The BFF forwards that key to order-service.

Successful checkout navigates to the persisted order URL and clears mutable cart
state. Reloading confirmation fetches the owned order again; navigation state
alone does not establish purchase success.

Receipt UI distinguishes pending, ready, delayed, and unavailable outcomes.
Polling uses bounded exponential backoff, stops on navigation/completion, and
provides retry after the wait budget. Receipt content renders as text. Order
history and confirmation remain based on order-service records, not the ledger's
item count or the current product catalogue.

## Implemented design and limitations

The storefront uses the shared cream/green palette, reusable panels, product
placeholders, responsive grids, and stacked mobile layouts. Loading, empty,
validation, authorization, and service-error states are part of the customer flow.

Payment collection, delivery addresses, favourites, and delivery scheduling are
not implemented customer capabilities. Do not add copy or working-looking
controls that imply these features exist.

## Development and verification

Follow [the frontend README](../README.md) for installation, service ports, Redis,
JWT configuration, and startup. Runtime requires the BFF and appropriate backend
services; browser fixtures are not a substitute for this stack.

Relevant existing commands from `frontend/`:

```sh
npm ci
npm run type-check
npm run lint
npm run format
npm test
npm run build
npm run test:e2e
```

Playwright supports `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` for an installed browser.
Its nine customer journeys use stateful API fixtures to exercise navigation,
session behavior, cart recovery, checkout, confirmation reload, history, and
receipt polling. Real PostgreSQL/Kafka integration is checked separately from
repository root with `mvn -B verify -Dspring.profiles.active=test`.

Documentation-only updates do not require application test runs. Verification
results belong in delivery notes; do not treat a previously passing run as proof
of an unverified deployment.

See [the full application architecture](../../docs/architecture-overview.md) for
service ownership, event delivery, migration handling, and backend boundaries.
