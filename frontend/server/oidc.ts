import { createHash, timingSafeEqual } from "node:crypto";
import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";
import { generateOpaqueValue } from "./auth-request-store.js";
import { safeReturnTo } from "./bff.js";
import type { OidcConfig } from "./config.js";

const TIMEOUT = 8_000;

export interface OidcDiscovery {
  authorizationEndpoint: string;
  endSessionEndpoint?: string;
  issuer: string;
  jwksUri: string;
  tokenEndpoint: string;
}

export interface OidcTokens {
  accessToken: string;
  idToken: string;
}

export interface PkcePair {
  challenge: string;
  verifier: string;
}

export interface OidcClient {
  authorizationUrl(params: {
    codeChallenge: string;
    nonce: string;
    state: string;
  }): Promise<string>;
  discovery(): Promise<OidcDiscovery>;
  exchangeCode(code: string, codeVerifier: string): Promise<OidcTokens>;
  verifyAccessToken(token: string): Promise<JWTPayload>;
  verifyIdToken(token: string, nonce: string): Promise<JWTPayload>;
}

export interface LoginTransaction {
  state: string;
  nonce: string;
  verifier: string;
  returnTo: string;
  expiresAt: number;
}

export { safeReturnTo } from "./bff.js";
export function newLogin(returnTo: unknown): LoginTransaction {
  return {
    state: generateOpaqueValue(32),
    nonce: generateOpaqueValue(32),
    verifier: generateOpaqueValue(32),
    returnTo: safeReturnTo(returnTo),
    expiresAt: Date.now() + 600_000,
  };
}

export class OidcError extends Error {
  constructor(readonly reason: string) {
    super(reason);
    this.name = "OidcError";
  }
}

/** PKCE S256 pair as required by OAuth 2.1 for public clients. */
export function createPkce(): PkcePair {
  const verifier = generateOpaqueValue(32);
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { challenge, verifier };
}

/** Constant-time string comparison to avoid leaking secrets via timing (CWE-208). */
function timingSafeEqualStrings(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

function requireString(doc: Record<string, unknown>, key: string): string {
  const value = doc[key];
  if (typeof value !== "string" || !value) {
    throw new OidcError(`discovery_missing_${key}`);
  }
  return value;
}

async function fetchDiscovery(discoveryUrl: string): Promise<OidcDiscovery> {
  let response: Response;
  try {
    response = await fetch(discoveryUrl, {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(TIMEOUT),
    });
  } catch {
    throw new OidcError("discovery_unavailable");
  }
  if (!response.ok) throw new OidcError("discovery_unavailable");
  const doc = (await response.json().catch(() => null)) as Record<
    string,
    unknown
  > | null;
  if (!doc) throw new OidcError("discovery_invalid");
  const endSessionEndpoint =
    typeof doc.end_session_endpoint === "string"
      ? doc.end_session_endpoint
      : undefined;
  return {
    authorizationEndpoint: requireString(doc, "authorization_endpoint"),
    endSessionEndpoint,
    issuer: requireString(doc, "issuer"),
    jwksUri: requireString(doc, "jwks_uri"),
    tokenEndpoint: requireString(doc, "token_endpoint"),
  };
}

export function createOidcClient(
  settings: OidcConfig,
  apiAudience: string,
): OidcClient {
  let discoveryPromise: Promise<OidcDiscovery> | undefined;
  let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

  function discovery(): Promise<OidcDiscovery> {
    discoveryPromise ??= fetchDiscovery(settings.discoveryUrl);
    return discoveryPromise;
  }

  async function getJwks() {
    if (!jwks) {
      const doc = await discovery();
      jwks = createRemoteJWKSet(new URL(doc.jwksUri));
    }
    return jwks;
  }

  return {
    async authorizationUrl({ codeChallenge, nonce, state }) {
      const doc = await discovery();
      const url = new URL(doc.authorizationEndpoint);
      url.searchParams.set("response_type", "code");
      url.searchParams.set("client_id", settings.clientId);
      url.searchParams.set("redirect_uri", settings.redirectUri);
      url.searchParams.set("scope", settings.scopes.join(" "));
      url.searchParams.set("state", state);
      url.searchParams.set("nonce", nonce);
      url.searchParams.set("code_challenge", codeChallenge);
      url.searchParams.set("code_challenge_method", "S256");
      return url.toString();
    },

    discovery,

    async exchangeCode(code, codeVerifier) {
      const doc = await discovery();
      const body = new URLSearchParams({
        grant_type: "authorization_code",
        code,
        code_verifier: codeVerifier,
        client_id: settings.clientId,
        redirect_uri: settings.redirectUri,
      });
      if (settings.clientSecret) {
        body.set("client_secret", settings.clientSecret);
      }
      let response: Response;
      try {
        response = await fetch(doc.tokenEndpoint, {
          method: "POST",
          headers: {
            "content-type": "application/x-www-form-urlencoded",
            accept: "application/json",
          },
          body,
          signal: AbortSignal.timeout(TIMEOUT),
        });
      } catch {
        throw new OidcError("token_endpoint_unavailable");
      }
      if (!response.ok) throw new OidcError("token_exchange_failed");
      const tokens = (await response.json().catch(() => null)) as Record<
        string,
        unknown
      > | null;
      const accessToken = tokens?.access_token;
      const idToken = tokens?.id_token;
      if (typeof accessToken !== "string" || typeof idToken !== "string") {
        throw new OidcError("token_response_invalid");
      }
      return { accessToken, idToken };
    },

    async verifyAccessToken(token) {
      return (
        await jwtVerify(token, await getJwks(), {
          algorithms: ["RS256"],
          audience: apiAudience,
          issuer: settings.issuer,
        })
      ).payload;
    },

    async verifyIdToken(token, nonce) {
      const { payload } = await jwtVerify(token, await getJwks(), {
        algorithms: ["RS256"],
        audience: settings.clientId,
        issuer: settings.issuer,
      });
      if (
        typeof payload.nonce !== "string" ||
        !timingSafeEqualStrings(payload.nonce, nonce)
      ) {
        throw new OidcError("nonce_mismatch");
      }
      return payload;
    },
  };
}
