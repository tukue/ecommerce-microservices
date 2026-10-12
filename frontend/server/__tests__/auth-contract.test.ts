// @vitest-environment node
import request from "supertest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createBff } from "../bff";
import { MemorySessionStore } from "../session-store";

const config = {
  auth: { mode: "password" as const, publicOrigin: "http://localhost:3000" },
  cookieSecure: false,
  jwt: {
    audience: "grocery-api",
    issuer: "https://issuer.test",
    jwksUri: "https://issuer.test/jwks",
  },
  port: 3000,
  serviceUrls: {
    cart: "http://cart",
    order: "http://order",
    product: "http://product",
    ledger: "http://ledger",
  },
};
const verifyToken = async () => ({
  sub: "u1",
  email: "u@example.com",
  exp: Math.floor(Date.now() / 1000) + 3600,
});
function token(exp = Math.floor(Date.now() / 1000) + 3600) {
  return `x.${Buffer.from(JSON.stringify({ sub: "u1", email: "u@example.com", exp })).toString("base64url")}.x`;
}
afterEach(() => vi.unstubAllGlobals());
describe("auth contract", () => {
  it("does not create a session when token verification fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ token: token() }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    const rejectToken = async () => {
      throw new Error("invalid signature");
    };
    const response = await request(
      createBff(config, new MemorySessionStore(), rejectToken),
    )
      .post("/api/auth/login")
      .send({ username: "u", password: "p" });
    expect(response.status).toBe(502);
    expect(response.headers["set-cookie"]).toBeUndefined();
  });

  it("sets an opaque HttpOnly cookie and never returns the JWT", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ token: token() }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    const response = await request(
      createBff(config, new MemorySessionStore(), verifyToken),
    )
      .post("/api/auth/login")
      .send({ username: "u", password: "p" });
    expect(response.status).toBe(200);
    expect(response.text).not.toContain("eyJ");
    expect(response.headers["set-cookie"][0]).toMatch(
      /grocery_session=.*HttpOnly.*SameSite=Lax/,
    );
    const me = await request(
      createBff(config, new MemorySessionStore(), verifyToken),
    )
      .get("/api/auth/me")
      .set("Cookie", response.headers["set-cookie"]);
    expect(me.status).toBe(401);
  });
  it("supports session lookup and logout in one agent", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ token: token() }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    );
    const agent = request.agent(
      createBff(config, new MemorySessionStore(), verifyToken),
    );
    await agent
      .post("/api/auth/login")
      .send({ username: "u", password: "p" })
      .expect(200);
    await agent
      .get("/api/auth/me")
      .expect(200, { userId: "u1", email: "u@example.com" });
    await agent.post("/api/auth/logout").expect(204);
    await agent.get("/api/auth/me").expect(401);
  });
});
