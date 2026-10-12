// @vitest-environment node
import request from "supertest";
import { describe, expect, it } from "vitest";
import { createBff } from "../bff";
import { MemoryAuthRequestStore } from "../auth-request-store";
import { OidcError, type OidcClient } from "../oidc";
import { MemorySessionStore } from "../session-store";
import type { BffConfig } from "../config";

const config: BffConfig = {
  auth: {
    mode: "oidc",
    oidc: {
      clientId: "grocery-storefront",
      discoveryUrl: "https://issuer.test/.well-known/openid-configuration",
      issuer: "https://issuer.test",
      redirectUri: "https://storefront.test/api/auth/callback",
      scopes: ["openid", "profile", "email"],
    },
    publicOrigin: "https://storefront.test",
  },
  cookieSecure: true,
  jwt: {
    audience: "grocery-api",
    issuer: "https://issuer.test",
    jwksUri: "https://issuer.test/jwks",
  },
  port: 3000,
  serviceUrls: {
    cart: "https://gateway.test",
    order: "https://gateway.test",
    product: "https://gateway.test",
    ledger: "https://gateway.test",
  },
};

const verifyToken = async () => ({ sub: "unused", exp: 9_999_999_999 });

function fakeOidcClient(overrides: Partial<OidcClient> = {}) {
  const authCalls: { codeChallenge: string; nonce: string; state: string }[] =
    [];
  const client: OidcClient = {
    async authorizationUrl(params) {
      authCalls.push(params);
      return `https://issuer.test/authorize?state=${params.state}&nonce=${params.nonce}&code_challenge=${params.codeChallenge}`;
    },
    async discovery() {
      return {
        authorizationEndpoint: "https://issuer.test/authorize",
        issuer: "https://issuer.test",
        jwksUri: "https://issuer.test/jwks",
        tokenEndpoint: "https://issuer.test/token",
      };
    },
    async exchangeCode() {
      return { accessToken: "access-token", idToken: "id-token" };
    },
    async verifyAccessToken() {
      return { sub: "u1", exp: Math.floor(Date.now() / 1000) + 3600 };
    },
    async verifyIdToken() {
      return {
        sub: "u1",
        email: "u@example.test",
        exp: Math.floor(Date.now() / 1000) + 3600,
      };
    },
    ...overrides,
  };
  return { authCalls, client };
}

function fixture(client: OidcClient, store = new MemoryAuthRequestStore()) {
  return {
    app: createBff(config, new MemorySessionStore(), verifyToken, {
      oidc: client,
      authRequests: store,
    }),
    store,
  };
}

describe("OIDC auth contract", () => {
  it("reports the active authentication mode", async () => {
    const { client } = fakeOidcClient();
    const { app } = fixture(client);
    await request(app).get("/api/auth/config").expect(200, { mode: "oidc" });
  });

  it("redirects sign-in to the provider with request identity and PKCE", async () => {
    const { client, authCalls } = fakeOidcClient();
    const { app } = fixture(client);
    const response = await request(app)
      .get("/api/auth/login?returnTo=/cart")
      .expect(302);
    const location = new URL(response.headers.location);
    expect(location.origin).toBe("https://issuer.test");
    expect(location.searchParams.get("state")).toBeTruthy();
    expect(location.searchParams.get("nonce")).toBeTruthy();
    expect(location.searchParams.get("code_challenge")).toBeTruthy();
    expect(authCalls[0].codeChallenge).toBe(
      location.searchParams.get("code_challenge"),
    );
    expect(response.headers["cache-control"]).toBe("no-store");
  });

  it("establishes an opaque cookie session on callback and returns to the requested path", async () => {
    const { client } = fakeOidcClient();
    const { app } = fixture(client);
    const login = await request(app)
      .get("/api/auth/login?returnTo=/cart")
      .expect(302);
    const state = new URL(login.headers.location).searchParams.get("state");
    const callback = await request(app)
      .get(`/api/auth/callback?code=abc&state=${state}`)
      .expect(302);
    expect(callback.headers.location).toBe("/cart");
    const cookie = callback.headers["set-cookie"][0];
    expect(cookie).toMatch(/grocery_session=.*HttpOnly.*SameSite=Lax/);
    expect(cookie).toContain("Secure");
    expect(callback.text).not.toContain("access-token");
    await request(app)
      .get("/api/auth/me")
      .set("Cookie", cookie)
      .expect(200, { userId: "u1", email: "u@example.test" });
  });

  it("rejects an unknown or replayed request identity", async () => {
    const { client } = fakeOidcClient();
    const { app, store } = fixture(client);
    const pending = await store.create({
      codeVerifier: "v",
      expiresAt: Date.now() + 60_000,
      nonce: "n",
      returnTo: "/cart",
    });
    await request(app)
      .get(`/api/auth/callback?code=abc&state=${pending.state}`)
      .expect(302);
    await request(app)
      .get(`/api/auth/callback?code=abc&state=${pending.state}`)
      .expect(400);
    await request(app)
      .get("/api/auth/callback?code=abc&state=unknown")
      .expect(400);
  });

  it("rejects a callback whose nonce does not match", async () => {
    const { client } = fakeOidcClient({
      async verifyIdToken() {
        throw new OidcError("nonce_mismatch");
      },
    });
    const { app, store } = fixture(client);
    const pending = await store.create({
      codeVerifier: "v",
      expiresAt: Date.now() + 60_000,
      nonce: "n",
      returnTo: "/cart",
    });
    await request(app)
      .get(`/api/auth/callback?code=abc&state=${pending.state}`)
      .expect(400);
  });

  it("fails safely when the provider is unavailable", async () => {
    const { client } = fakeOidcClient({
      async authorizationUrl() {
        throw new OidcError("discovery_unavailable");
      },
      async exchangeCode() {
        throw new OidcError("token_endpoint_unavailable");
      },
    });
    const { app, store } = fixture(client);
    await request(app).get("/api/auth/login").expect(503);
    const pending = await store.create({
      codeVerifier: "v",
      expiresAt: Date.now() + 60_000,
      nonce: "n",
      returnTo: "/cart",
    });
    await request(app)
      .get(`/api/auth/callback?code=abc&state=${pending.state}`)
      .expect(502);
  });

  it("does not expose the password login in OIDC mode", async () => {
    const { client } = fakeOidcClient();
    const { app } = fixture(client);
    await request(app)
      .post("/api/auth/login")
      .send({ username: "u", password: "p" })
      .expect(404);
  });

  it("redirects to a safe default for an external return destination", async () => {
    const { client } = fakeOidcClient();
    const { app } = fixture(client);
    const login = await request(app)
      .get("/api/auth/login?returnTo=https://evil.test")
      .expect(302);
    const state = new URL(login.headers.location).searchParams.get("state");
    const callback = await request(app)
      .get(`/api/auth/callback?code=abc&state=${state}`)
      .expect(302);
    expect(callback.headers.location).toBe("/products");
  });

  it("re-validates the stored return destination at callback (CWE-601)", async () => {
    const { client } = fakeOidcClient();
    const escapedStore = {
      async create() {
        throw new Error("unused");
      },
      async consume() {
        return {
          codeVerifier: "v",
          expiresAt: Date.now() + 60_000,
          nonce: "n",
          returnTo: "https://evil.test/steal",
          state: "s",
        };
      },
    };
    const app = createBff(config, new MemorySessionStore(), verifyToken, {
      oidc: client,
      authRequests: escapedStore,
    });
    const callback = await request(app)
      .get("/api/auth/callback?code=abc&state=s")
      .expect(302);
    expect(callback.headers.location).toBe("/products");
  });

  it("deletes the session on logout", async () => {
    const { client } = fakeOidcClient();
    const { app } = fixture(client);
    const login = await request(app).get("/api/auth/login").expect(302);
    const state = new URL(login.headers.location).searchParams.get("state");
    const callback = await request(app)
      .get(`/api/auth/callback?code=abc&state=${state}`)
      .expect(302);
    const cookie = callback.headers["set-cookie"][0];
    await request(app).get("/api/auth/me").set("Cookie", cookie).expect(200);
    await request(app)
      .post("/api/auth/logout")
      .set("Cookie", cookie)
      .expect(204);
    await request(app).get("/api/auth/me").set("Cookie", cookie).expect(401);
  });
});
