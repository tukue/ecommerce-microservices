// @vitest-environment node
import { readFileSync } from "node:fs";
import express from "express";
import request from "supertest";
import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({ getRuntime: vi.fn() }));
vi.mock("../runtime.js", () => ({ getRuntime: mocks.getRuntime }));
import handler from "../../api/[...path]";
beforeEach(() => vi.clearAllMocks());
describe("Vercel routing", () => {
  const config = JSON.parse(
    readFileSync(new URL("../../vercel.json", import.meta.url), "utf8"),
  );
  it.each(["/api", "/api/", "/api/auth/session", "/api/unknown"])(
    "routes %s to the BFF first",
    (path) => {
      expect(new RegExp(`^${config.routes[0].src}$`).test(path)).toBe(true);
      expect(config.routes[0].dest).toBe("/api/[...path]");
      expect(config.routes[1]).toEqual({ handle: "filesystem" });
    },
  );
  it("keeps missing assets out of the SPA fallback", () => {
    expect(config.routes[2].status).toBe(404);
    expect(
      new RegExp(`^${config.routes[2].src}$`).test("/assets/missing.js"),
    ).toBe(true);
    expect(config.routes[3].dest).toBe("/index.html");
  });
  it("preserves path, query, status and multiple cookies", async () => {
    const app = express();
    app.use((req, res) =>
      res
        .status(201)
        .append("Set-Cookie", "session=one; HttpOnly")
        .append("Set-Cookie", "csrf=two")
        .json({ path: req.originalUrl }),
    );
    mocks.getRuntime.mockResolvedValue({ app });
    const outer = express();
    outer.use(handler);
    const response = await request(outer).get(
      "/api/auth/callback?code=example",
    );
    expect(response.status).toBe(201);
    expect(response.body.path).toBe("/api/auth/callback?code=example");
    expect(response.headers["set-cookie"]).toHaveLength(2);
  });
  it("returns a generic non-cacheable 503 on initialization failure", async () => {
    mocks.getRuntime.mockRejectedValue(
      new Error("redis://private:secret@host"),
    );
    const outer = express();
    outer.use(handler);
    const response = await request(outer).get("/api/customer/cart");
    expect(response.status).toBe(503);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(response.body).toEqual({
      status: 503,
      message: "Service unavailable",
    });
  });
});
