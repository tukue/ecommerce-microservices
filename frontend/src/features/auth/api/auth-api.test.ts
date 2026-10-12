// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

import { getAuthMode, loginUrl } from "./auth-api";

afterEach(() => vi.unstubAllGlobals());

describe("loginUrl", () => {
  it("builds the provider sign-in URL", () => {
    expect(loginUrl()).toBe("/api/auth/login");
  });

  it("encodes the return destination", () => {
    expect(loginUrl("/cart?ref=nav")).toBe(
      "/api/auth/login?returnTo=%2Fcart%3Fref%3Dnav",
    );
  });
});

describe("getAuthMode", () => {
  it("returns the configured mode", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ mode: "oidc" }), { status: 200 }),
        ),
    );
    await expect(getAuthMode()).resolves.toBe("oidc");
  });

  it("defaults to password mode on failure", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(getAuthMode()).resolves.toBe("password");
  });
});
