# Stripe test checkout

The checkout flow uses Stripe-hosted Checkout in test mode. The application
never receives or stores card numbers or security codes. Orders remain
`PENDING` until a signed Stripe webhook confirms payment; receipt events are
published only after that confirmation.

## Local setup

1. Copy `microservices/.env.example` to `microservices/.env`, set Stripe
   test-mode API keys from the Stripe Dashboard, and populate
   `INTERNAL_SERVICE_KEY` with one strong random secret (for
   example, `openssl rand -hex 32`). Compose requires this shared key for the
   cart, order, and product services so expiry compensation can release stock
   and reopen carts.
   Do not commit `.env` or put live Stripe keys in local development files.
2. Start the stack with
   `docker compose -f microservices/docker-compose.yml up --build`.
3. Forward Stripe events to the public gateway:
   `stripe listen --forward-to localhost:8085/api/webhooks/stripe`.
   Put the signing secret printed by Stripe CLI in `STRIPE_WEBHOOK_SECRET`.
4. Restart the order service after changing either Stripe key.

The default return URLs target the Vite app at `http://localhost:5173`. Override
`STRIPE_FRONTEND_BASE_URL` when the frontend runs elsewhere.
Only SEK orders are currently accepted by Stripe checkout. Checkout sessions
expire after 35 minutes; the signed expiry event cancels the pending order,
releases its stock reservations, and reopens its cart.

The `PATCH /api/customer/orders/{id}/status` endpoint cannot mark an order
completed. Only the verified Stripe webhook can complete payment and trigger
the order receipt event.

For deployments, inject test or live keys through the platform's secret
manager, configure an HTTPS frontend return URL, and register the HTTPS
`/api/webhooks/stripe` endpoint in Stripe. Never use a live key in the local
test-mode workflow.

## Implementation

### Payment lifecycle

Orders are created `PENDING` by `POST /api/customer/checkout` and stay
`PENDING` until Stripe confirms payment. The only path to `COMPLETED` is the
verified webhook (`OrderService.completePayment`); the customer-facing
`PATCH /api/customer/orders/{id}/status` endpoint always rejects with
`InvalidOrderStateException`. Completing payment persists the order and
enqueues the `OrderCreatedEvent` receipt event. Expiry
(`OrderService.expirePayment`) releases stock reservations, reopens the cart,
and moves the order to `CANCELLED`.

### Backend (`order-service`)

- `controller/PaymentController.java` — `POST /api/customer/orders/{orderId}/payment-session`
  (requires `SCOPE_order:write`). Delegates to
  `StripePaymentService.createCheckoutSession`, which enforces order ownership,
  `PENDING`-only status, and SEK-only currency, then reuses an existing `OPEN`
  attempt or creates a new Stripe Checkout Session.
- `controller/StripeWebhookController.java` — `POST /webhooks/stripe`, public
  (see `SecurityConfig`: CSRF disabled, stateless bearer-token API). The
  `Stripe-Signature` header is required and a missing/blank signature is
  rejected as `InvalidStripeWebhookException` (-> 400) before signature
  verification.
- `service/StripePaymentService.java`
  - `createStripeSession` builds line items from order lines in minor units,
    rejects non-positive amounts, verifies the summed lines equal the order
    total, and sends an idempotency key `order-{id}-attempt-{n}`. Success and
    cancel URLs point at the Vite app (`/confirmation/{id}`,
    `/checkout?payment=cancelled&orderId={id}`); sessions expire after
    35 minutes. Card payment is requested via `allowedPaymentMethodTypes`
    (`stripe-java` v34 removed `paymentMethodTypes`).
  - `handleWebhook` verifies the signature with `stripe.webhook-secret`,
    ignores unknown event types, loads the `Session`, matches it to a stored
    `PaymentAttempt` (session id + metadata `orderId` must agree), and routes
    `checkout.session.completed` / `async_payment_succeeded` (with
    `paymentStatus=paid`) to `completePayment`, and `expired` /
    `async_payment_failed` to expiry.
  - `reconcileExpiredCheckouts` (every
    `app.payments.expiry-scan-delay-ms`, default 60s) re-checks stale `PENDING`
    orders against Stripe and completes or expires them, so missed webhooks
    cannot strand stock.
- `model/PaymentAttempt.java` + `db/migration/order/V5__payment_attempts.sql` —
  `payment_attempt` rows (`order_id`, `attempt_number`, `stripe_session_id`,
  `checkout_url`, `status OPEN/PAID/EXPIRED`) with unique constraints on
  `(order_id, attempt_number)` and `stripe_session_id`. The order row is locked
  (`findByIdForUpdate`) during session creation, so concurrent retries cannot
  create duplicate open sessions.

### Cross-service compensation

Payment expiry must undo reservations without a user token, so it uses a
shared `services.internal-key` (`X-Internal-Service-Key`, constant-time
comparison):

- `RestCartClient.markOpen` calls
  `POST /api/customer/internal/carts/{id}/reopen` on cart-service, which
  reopens `CHECKED_OUT` carts without ownership checks (trusts the key).
- `RestProductClient.releaseAfterPaymentExpiry` calls
  `DELETE /products/reservations/internal/{key}` on product-service.
- Without the key configured, both clients fail with service-unavailable
  rather than silently skipping compensation.

### Gateway

`gateway-service` routes `POST /api/webhooks/stripe` to order-service with
`stripPrefix(1)` and no rate limiter (Stripe retries delivery); the path is
`permitAll` in the gateway `SecurityConfig` because Stripe authenticates via
the webhook signature, not JWT. Customer payment routes
(`/api/customer/orders/**`) keep JWT auth + rate limiting. The BFF
(`frontend/server/proxy.ts`) forwards
`POST /api/customer/orders/{id}/payment-session` to the order service.

### Frontend

- `features/orders/api/checkout-client.ts` — `submitCheckout` (requires an
  idempotency key) then `createPaymentSession(orderId)` (`POST
  /api/customer/orders/{id}/payment-session`, validates `checkoutUrl`).
- `features/orders/components/checkout-page.tsx` — on submit, creates the
  order, creates the payment session, clears the cart, and redirects to the
  hosted Stripe URL. A `?payment=cancelled&orderId=` return shows a
  "Payment not completed" state with a retry button that reuses the open
  session.
- `features/orders/components/confirmation-page.tsx` — polls the order while
  `PENDING` (2s, up to 15 times) and renders "Order Confirmed" + receipt only
  for `COMPLETED`, "Payment processing" while pending, and a cancelled notice
  otherwise.

### Configuration reference

| Property | Purpose | Default |
|---|---|---|
| `stripe.secret-key` | Stripe API key for session create/retrieve | empty (payments unavailable) |
| `stripe.webhook-secret` | Signing secret for webhook verification | empty (webhooks rejected) |
| `stripe.frontend-base-url` | Success/cancel return base URL (validated HTTP(S) origin) | `http://localhost:5173` |
| `app.payments.expiry-scan-delay-ms` | Stale-checkout reconciliation interval | `60000` |
| `services.internal-key` | Shared key for cart/product compensation calls | empty (compensation unavailable) |

### Testing

- Unit: `StripePaymentServiceTest` (ownership, open-session reuse),
  `OrderServiceTest` (payment completion emits receipt event, expiry releases
  reservations and reopens cart).
- E2E (`frontend/e2e/helpers.ts`) mocks `POST
  /api/customer/orders/{id}/payment-session` by marking the mocked order
  `COMPLETED` (simulating the webhook) and returning a `checkoutUrl` back to
  the app's confirmation page; the receipt mock stays `pending` for the first
  polls so the receipt-pending UI is observable.
