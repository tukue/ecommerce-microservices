import { z } from "zod";

const url = z.string().url();

/**
 * Environment schema for the Node.js BFF.
 *
 * After introducing Spring Cloud Gateway, the BFF no longer holds a map of
 * individual service URLs. All backend traffic is routed through the gateway,
 * which owns the route table. The BFF's sole responsibility is session
 * management (login, logout, /me) and cookie handling.
 *
 * Identity is provider-neutral: in production the BFF performs an OIDC
 * authorization code + PKCE flow against an external provider. The development
 * password mechanism is only available when explicitly selected.
 */
const envSchema = z.object({
  BFF_PORT: z.coerce.number().int().positive().max(65535).default(3000),

  /**
   * Spring Cloud Gateway base URL.
   * Dev:    http://localhost:8085
   * Docker: http://gateway-service:8080  (resolved by Docker Compose DNS)
   */
  GATEWAY_URL: url.default("http://localhost:8085"),

  NODE_ENV: z
    .enum(["development", "test", "production"])
    .default("development"),

  JWT_AUDIENCE: z.string().min(1).default("grocery-api"),

  /**
   * JWKS discovery URI used by the BFF to verify tokens it receives from the
   * gateway on the /api/auth/me path. Defaults to the gateway's own issuer
   * (cart-service in dev) via the gateway's JWT_ISSUER_URI.
   */
  JWT_ISSUER_URI: url.optional(),
  JWT_JWKS_URI: url.optional(),

  REDIS_URL: url.optional(),

  /**
   * Active authentication mode. `oidc` performs an external authorization code
   * + PKCE flow; `password` is the development-only mechanism. Production
   * defaults to `oidc` and rejects `password`.
   */
  AUTH_MODE: z.enum(["oidc", "password"]).optional(),
  OIDC_ISSUER_URI: url.optional(),
  OIDC_CLIENT_ID: z.string().min(1).optional(),
  OIDC_CLIENT_SECRET: z.string().min(1).optional(),
  OIDC_REDIRECT_URI: url.optional(),
  OIDC_SCOPES: z.string().default("openid profile email"),
  PUBLIC_ORIGIN: url.optional(),
});

export type AuthMode = "oidc" | "password";

export interface OidcConfig {
  clientId: string;
  clientSecret?: string;
  discoveryUrl: string;
  issuer: string;
  redirectUri: string;
  scopes: string[];
}

export interface AuthConfig {
  mode: AuthMode;
  oidc?: OidcConfig;
  publicOrigin: string;
}
/**
 * Service URLs visible to the BFF.
 *
 * All four point at the gateway — the gateway's route table handles
 * dispatching to the real service. This keeps the BFF's routing concern simple:
 * it only needs to know the gateway address.
 */
export type ServiceUrls = {
  /** Used for /api/catalog/** routes */
  product: string;
  /** Used for /api/customer/cart routes */
  cart: string;
  /** Used for /api/customer/orders and /api/customer/checkout routes */
  order: string;
  /** Used for /api/customer/ledger routes */
  ledger: string;
};

export interface BffConfig {
  auth: AuthConfig;
  cookieSecure: boolean;
  jwt: { audience: string; issuer: string; jwksUri: string };
  port: number;
  redisUrl?: string;
  serviceUrls: ServiceUrls;
}

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

function isLocalHost(hostname: string): boolean {
  return (
    hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1"
  );
}

/** HTTPS is required unless the issuer is a local development host. */
export function isSecureIssuer(issuer: string): boolean {
  try {
    const parsed = new URL(issuer);
    return parsed.protocol === "https:" || isLocalHost(parsed.hostname);
  } catch {
    return false;
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BffConfig {
  const parsed = envSchema.parse(env);
  const gatewayUrl = parsed.GATEWAY_URL;
  const production = parsed.NODE_ENV === "production";

  const mode: AuthMode = parsed.AUTH_MODE ?? (production ? "oidc" : "password");

  const fatal: string[] = [];
  if (production && mode !== "oidc") {
    fatal.push("AUTH_MODE must be oidc in production");
  }
  if (mode === "oidc") {
    if (!parsed.OIDC_ISSUER_URI) fatal.push("OIDC_ISSUER_URI is required");
    if (!parsed.OIDC_CLIENT_ID) fatal.push("OIDC_CLIENT_ID is required");
    if (!parsed.OIDC_REDIRECT_URI) fatal.push("OIDC_REDIRECT_URI is required");
    if (parsed.OIDC_ISSUER_URI && !isSecureIssuer(parsed.OIDC_ISSUER_URI)) {
      fatal.push("OIDC_ISSUER_URI must use HTTPS");
    }
  }
  if (fatal.length > 0) {
    // Message intentionally omits all configured values so secrets never leak.
    throw new Error(`Unsafe identity configuration: ${fatal.join("; ")}`);
  }

  const oidc: OidcConfig | undefined =
    mode === "oidc"
      ? {
          clientId: parsed.OIDC_CLIENT_ID as string,
          clientSecret: parsed.OIDC_CLIENT_SECRET,
          discoveryUrl: `${trimTrailingSlash(parsed.OIDC_ISSUER_URI as string)}/.well-known/openid-configuration`,
          issuer: trimTrailingSlash(parsed.OIDC_ISSUER_URI as string),
          redirectUri: parsed.OIDC_REDIRECT_URI as string,
          scopes: parsed.OIDC_SCOPES.split(/\s+/).filter(Boolean),
        }
      : undefined;

  // Access-token verification issuer: the external provider in OIDC mode, an
  // explicitly supplied JWT issuer, or the gateway (dev demo issuer) fallback.
  const issuer = parsed.JWT_ISSUER_URI ?? oidc?.issuer ?? gatewayUrl;
  const jwksUri =
    parsed.JWT_JWKS_URI ?? `${trimTrailingSlash(issuer)}/.well-known/jwks.json`;

  const publicOrigin =
    parsed.PUBLIC_ORIGIN ??
    (parsed.OIDC_REDIRECT_URI
      ? new URL(parsed.OIDC_REDIRECT_URI).origin
      : gatewayUrl);

  return {
    auth: { mode, oidc, publicOrigin: trimTrailingSlash(publicOrigin) },
    cookieSecure: production,
    jwt: {
      audience: parsed.JWT_AUDIENCE,
      issuer,
      jwksUri,
    },
    port: parsed.BFF_PORT,
    redisUrl: parsed.REDIS_URL,
    // All service URL slots point at the gateway — the gateway routes internally
    serviceUrls: {
      cart: gatewayUrl,
      order: gatewayUrl,
      product: gatewayUrl,
      ledger: gatewayUrl,
    },
  };
}
