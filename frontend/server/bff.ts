import cookieParser from "cookie-parser";
import express, {
  type NextFunction,
  type Request,
  type Response,
} from "express";
import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";
import { pathToFileURL } from "node:url";
import { z } from "zod";
import { loadConfig, type BffConfig } from "./config.js";
import { resolveService } from "./proxy.js";
import {
  RedisSessionStore,
  type SessionRecord,
  type SessionStore,
} from "./session-store.js";
import {
  generateOpaqueValue,
  MemoryAuthRequestStore,
  RedisAuthRequestStore,
  type AuthRequestStore,
} from "./auth-request-store.js";
import {
  createOidcClient,
  createPkce,
  OidcError,
  type OidcClient,
} from "./oidc.js";

const COOKIE_NAME = "grocery_session";
const TIMEOUT = 8_000;
const AUTH_REQUEST_TTL_MS = 10 * 60 * 1000;
const DEFAULT_RETURN_TO = "/products";
export type TokenVerifier = (token: string) => Promise<JWTPayload>;

export interface BffDependencies {
  authRequests?: AuthRequestStore;
  oidc?: OidcClient;
}

export function createTokenVerifier(config: BffConfig): TokenVerifier {
  const jwks = createRemoteJWKSet(new URL(config.jwt.jwksUri));
  return async (token) =>
    (
      await jwtVerify(token, jwks, {
        algorithms: ["RS256"],
        audience: config.jwt.audience,
        issuer: config.jwt.issuer,
      })
    ).payload;
}

async function verifiedIdentity(token: string, verifyToken: TokenVerifier) {
  const payload = await verifyToken(token);
  const email = typeof payload.email === "string" ? payload.email : "";
  const userId = typeof payload.sub === "string" ? payload.sub : email;
  const expiresAt =
    typeof payload.exp === "number"
      ? payload.exp * 1000
      : Date.now() + 3_600_000;
  if (!email || !userId || expiresAt <= Date.now())
    throw new Error("Invalid identity claims");
  return { email, expiresAt, userId };
}

/** Derive a session identity from verified OIDC claims, provider-neutrally. */
function identityFromClaims(idClaims: JWTPayload, accessClaims: JWTPayload) {
  const sub = typeof idClaims.sub === "string" ? idClaims.sub : "";
  const email =
    (typeof idClaims.email === "string" && idClaims.email) ||
    (typeof idClaims.preferred_username === "string" &&
      idClaims.preferred_username) ||
    sub;
  const now = Date.now();
  const idExp =
    typeof idClaims.exp === "number" ? idClaims.exp * 1000 : now + 3_600_000;
  const accessExp =
    typeof accessClaims.exp === "number" ? accessClaims.exp * 1000 : idExp;
  const expiresAt = Math.min(idExp, accessExp);
  if (!sub || expiresAt <= now) throw new OidcError("invalid_identity");
  return { email, expiresAt, userId: sub };
}

/** Accept only same-origin, internal return destinations. */
export function safeReturnTo(value: unknown): string {
  const candidate = typeof value === "string" ? value : "";
  if (
    !candidate.startsWith("/") ||
    candidate.startsWith("//") ||
    candidate.includes("\\") ||
    /[\u0000-\u001f]/.test(candidate)
  ) {
    return DEFAULT_RETURN_TO;
  }
  return candidate;
}

function publicSession(session: SessionRecord) {
  return { email: session.email, userId: session.userId };
}

function setSessionCookie(
  res: Response,
  config: BffConfig,
  session: SessionRecord,
) {
  res.cookie(COOKIE_NAME, session.id, {
    httpOnly: true,
    maxAge: Math.max(0, session.expiresAt - Date.now()),
    path: "/",
    sameSite: "lax",
    secure: config.cookieSecure,
  });
}

function sendError(
  res: Response,
  status: number,
  message: string,
  path?: string,
) {
  res.status(status).json({
    error: status >= 500 ? "Bad Gateway" : "Request Failed",
    message,
    path,
    status,
  });
}

export function createBff(
  config: BffConfig,
  sessions: SessionStore,
  verifyToken: TokenVerifier = createTokenVerifier(config),
  deps: BffDependencies = {},
) {
  const oidc =
    deps.oidc ??
    (config.auth.oidc
      ? createOidcClient(config.auth.oidc, config.jwt.audience)
      : undefined);
  const authRequests = deps.authRequests ?? new MemoryAuthRequestStore();
  const app = express();
  app.disable("x-powered-by");
  app.use(cookieParser());
  app.use(express.json({ limit: "64kb" }));
  app.get("/health", (_req, res) => res.json({ status: "ok" }));
  app.use("/api/auth", (_req, res, next) => {
    res.setHeader("Cache-Control", "no-store");
    next();
  });

  app.get("/api/auth/config", (_req, res) => {
    res.json({ mode: config.auth.mode });
  });

  // OIDC mode: begin sign-in by redirecting to the external provider.
  app.get("/api/auth/login", async (req, res) => {
    if (config.auth.mode !== "oidc" || !oidc) {
      sendError(res, 404, "Not found", req.path);
      return;
    }
    try {
      const pkce = createPkce();
      const request = await authRequests.create({
        codeVerifier: pkce.verifier,
        expiresAt: Date.now() + AUTH_REQUEST_TTL_MS,
        nonce: generateOpaqueValue(),
        returnTo: safeReturnTo(req.query.returnTo),
      });
      const url = await oidc.authorizationUrl({
        codeChallenge: pkce.challenge,
        nonce: request.nonce,
        state: request.state,
      });
      res.redirect(302, url);
    } catch {
      sendError(res, 503, "Identity provider unavailable", req.path);
    }
  });

  // OIDC mode: handle the provider redirect and establish the session.
  app.get("/api/auth/callback", async (req, res) => {
    if (config.auth.mode !== "oidc" || !oidc) {
      sendError(res, 404, "Not found", req.path);
      return;
    }
    const request = await authRequests.consume(
      typeof req.query.state === "string" ? req.query.state : undefined,
    );
    if (!request) {
      sendError(res, 400, "Invalid or expired sign-in request", req.path);
      return;
    }
    const code = typeof req.query.code === "string" ? req.query.code : "";
    if (!code) {
      sendError(res, 400, "Missing authorization code", req.path);
      return;
    }
    try {
      const tokens = await oidc.exchangeCode(code, request.codeVerifier);
      const idClaims = await oidc.verifyIdToken(tokens.idToken, request.nonce);
      const accessClaims = await oidc.verifyAccessToken(tokens.accessToken);
      const session = await sessions.create({
        ...identityFromClaims(idClaims, accessClaims),
        jwt: tokens.accessToken,
      });
      setSessionCookie(res, config, session);
      // Re-validate at the redirect site so a tampered or legacy stored value
      // can never become an open redirect (CWE-601).
      res.redirect(302, safeReturnTo(request.returnTo));
    } catch (error) {
      sendError(
        res,
        error instanceof OidcError &&
          (error.reason === "nonce_mismatch" ||
            error.reason === "invalid_identity")
          ? 400
          : 502,
        "Sign-in could not be completed",
        req.path,
      );
    }
  });

  // Development password login. Never available when OIDC mode is active.
  app.post("/api/auth/login", async (req, res) => {
    if (config.auth.mode !== "password") {
      sendError(res, 404, "Not found", req.path);
      return;
    }
    const { username, password } = req.body ?? {};
    if (
      typeof username !== "string" ||
      !username.trim() ||
      typeof password !== "string" ||
      !password
    ) {
      sendError(res, 400, "Username and password are required", req.path);
      return;
    }
    try {
      const upstream = await fetch(`${config.serviceUrls.cart}/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ username, password }),
        signal: AbortSignal.timeout(TIMEOUT),
      });
      const body = (await upstream.json().catch(() => ({}))) as {
        token?: unknown;
      };
      if (!upstream.ok) {
        res.status(upstream.status).json(body);
        return;
      }
      if (typeof body.token !== "string") {
        sendError(
          res,
          502,
          "Identity service returned an invalid response",
          req.path,
        );
        return;
      }
      const session = await sessions.create({
        ...(await verifiedIdentity(body.token, verifyToken)),
        jwt: body.token,
      });
      setSessionCookie(res, config, session);
      res.json(publicSession(session));
    } catch {
      sendError(res, 502, "Authentication service unavailable", req.path);
    }
  });
  app.post("/api/auth/logout", async (req, res) => {
    await sessions.delete(req.cookies[COOKIE_NAME]);
    res.clearCookie(COOKIE_NAME, {
      httpOnly: true,
      path: "/",
      sameSite: "lax",
      secure: config.cookieSecure,
    });
    res.status(204).end();
  });
  app.get("/api/auth/me", async (req, res) => {
    const session = await sessions.get(req.cookies[COOKIE_NAME]);
    if (!session) {
      sendError(res, 401, "Not authenticated", req.path);
      return;
    }
    res.json(publicSession(session));
  });
  app.all("/api/*splat", async (req, res) => {
    const route = resolveService(
      req.method,
      req.originalUrl,
      config.serviceUrls,
    );
    if (!route) {
      sendError(res, 404, "Route not found", req.path);
      return;
    }
    const session = await sessions.get(req.cookies[COOKIE_NAME]);
    if (route.protected && !session) {
      sendError(res, 401, "Not authenticated", req.path);
      return;
    }
    const queryIndex = req.originalUrl.indexOf("?");
    const query = queryIndex >= 0 ? req.originalUrl.slice(queryIndex) : "";
    const headers: Record<string, string> = { accept: "application/json" };
    if (req.is("application/json"))
      headers["content-type"] = "application/json";
    if (session) headers.authorization = `Bearer ${session.jwt}`;
    if (
      req.path === "/api/customer/checkout" &&
      typeof req.body?.idempotencyKey === "string"
    )
      headers["idempotency-key"] = req.body.idempotencyKey;
    try {
      const upstream = await fetch(
        `${route.target}${route.upstreamPath}${query}`,
        {
          method: req.method,
          headers,
          body: ["GET", "HEAD"].includes(req.method)
            ? undefined
            : JSON.stringify(req.body ?? {}),
          signal: AbortSignal.timeout(TIMEOUT),
        },
      );
      const receiptMatch = req.path.match(
        /^\/api\/customer\/ledger\/orders\/(\d+)\/receipt$/,
      );
      if (receiptMatch && upstream.status === 404) {
        const owned = await fetch(
          `${config.serviceUrls.order}/api/customer/orders/${receiptMatch[1]}`,
          { headers, signal: AbortSignal.timeout(TIMEOUT) },
        );
        if (!owned.ok) {
          sendError(
            res,
            owned.status,
            owned.status === 401
              ? "Please sign in to continue"
              : owned.status === 403
                ? "Access denied"
                : owned.status === 404
                  ? "Order not found"
                  : "Order service unavailable",
            req.path,
          );
          return;
        }
        const order = z
          .object({
            id: z.number().int().positive(),
            userId: z.string().min(1),
          })
          .safeParse(await owned.json());
        if (!order.success || order.data.id !== Number(receiptMatch[1])) {
          sendError(
            res,
            502,
            "Order service returned an invalid response",
            req.path,
          );
          return;
        }
        if (order.data.userId !== session?.userId) {
          sendError(res, 403, "Access denied", req.path);
          return;
        }
        res.status(202).json({ status: "pending" });
        return;
      }
      if (receiptMatch && upstream.ok) {
        const content = await upstream.text();
        if (!content.trim()) {
          sendError(
            res,
            502,
            "Receipt service returned an invalid response",
            req.path,
          );
          return;
        }
        res.json({ status: "ready", content });
        return;
      }
      res
        .status(upstream.status)
        .type(upstream.headers.get("content-type") ?? "application/json")
        .send(Buffer.from(await upstream.arrayBuffer()));
    } catch {
      sendError(res, 502, "Service unavailable", req.path);
    }
  });
  app.use((error: unknown, req: Request, res: Response, _next: NextFunction) =>
    sendError(
      res,
      400,
      error instanceof Error ? error.message : "Invalid request",
      req.path,
    ),
  );
  return app;
}

const isMain =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  const config = loadConfig();
  if (!config.redisUrl)
    throw new Error("REDIS_URL is required for the BFF session store");
  const sessions = await RedisSessionStore.connect(config.redisUrl);
  const authRequests =
    config.auth.mode === "oidc"
      ? await RedisAuthRequestStore.connect(config.redisUrl)
      : undefined;
  createBff(config, sessions, createTokenVerifier(config), {
    authRequests,
  }).listen(config.port, () =>
    // The startup message is operational output for the standalone process.
    // eslint-disable-next-line no-console
    console.log(`Grocery BFF listening on http://localhost:${config.port}`),
  );
}
