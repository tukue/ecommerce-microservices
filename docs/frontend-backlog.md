# Frontend Backlog

This file tracks frontend work that is intentionally not implemented in the
current product and cart foundation.

## Customer authentication

- [x] Revalidate the shared cookie session when a tab regains focus or becomes
  visible. Hide the previous identity during verification, detect sign-out and
  account changes, and ignore responses superseded by a newer sign-in.
  This increment does not refresh expired provider tokens.

- [ ] Implement the production sign-in/session flow that writes a short-lived,
  httpOnly `access_token` cookie. The add-to-cart Server Action already reads
  this cookie on the server and returns an accessible sign-in message when it
  is absent; it does not expose a token to browser JavaScript.
- [ ] Add session expiry, refresh, sign-out, and CSRF protections appropriate
  to the selected identity provider and deployment topology.

## Verification and delivery

- [ ] Restore a working local `npm ci` environment and run `npm run lint`,
  `npm run type-check`, `npm test`, and `npm run build`. The local dependency
  installation stalled before `tsc` and Vitest became available.
- [ ] Add browser-level coverage against an authenticated, deployed cart and
  product environment once the session flow exists.
# Customer journey integration status

The authenticated customer journey is implemented through the standalone BFF: catalogue list/search/detail, opaque-cookie sign-in, server-authoritative cart mutations, retry-safe checkout, reloadable confirmation, and order history. Canonical Vite components use kebab-case paths; the obsolete Next.js route tree and PascalCase duplicates were removed.

The BFF now uses a shared Redis session store suitable for restarts and horizontal scaling. Remaining operational follow-up: replace the demo identity provider before production use and provision managed Redis with encryption, authentication, backups, and monitoring.
