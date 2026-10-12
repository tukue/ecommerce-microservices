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
