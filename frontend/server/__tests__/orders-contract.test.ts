// @vitest-environment node
import request from "supertest";
import { describe, expect, it, vi } from "vitest";
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
describe("order proxy ownership boundary", () => {
  it("requires a session and forwards only its bearer token", async () => {
    const sessions = new MemorySessionStore();
    const session = await sessions.create({
      jwt: "owner-token",
      userId: "owner",
      email: "o@example.com",
      expiresAt: Date.now() + 10000,
    });
    const f = vi.fn().mockResolvedValue(
      new Response("[]", {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", f);
    const server = createBff(config, sessions).listen();
    await request(server).get("/api/customer/orders").expect(401);
    await request(server)
      .get("/api/customer/orders")
      .set("Cookie", `grocery_session=${session.id}`)
      .expect(200);
    server.close();
    expect(f.mock.calls[0][1].headers.authorization).toBe("Bearer owner-token");
  });
});
