// @vitest-environment node
import { describe, expect, it } from "vitest";
import { loadConfig } from "../config";

describe("loadConfig identity hardening", () => {
  it("defaults to development password mode", () => {
    const config = loadConfig({});
    expect(config.auth.mode).toBe("password");
    expect(config.auth.oidc).toBeUndefined();
    expect(config.cookieSecure).toBe(false);
  });

  it("requires OIDC settings in production", () => {
    expect(() => loadConfig({ NODE_ENV: "production" })).toThrow(
      /Unsafe identity configuration/,
    );
  });

  it("rejects password mode in production", () => {
    expect(() =>
      loadConfig({
        AUTH_MODE: "password",
        NODE_ENV: "production",
      }),
    ).toThrow(/AUTH_MODE must be oidc in production/);
  });

  it("rejects a non-HTTPS issuer", () => {
    expect(() =>
      loadConfig({
        AUTH_MODE: "oidc",
        OIDC_CLIENT_ID: "client",
        OIDC_ISSUER_URI: "http://id.example.com",
        OIDC_REDIRECT_URI: "https://storefront.test/api/auth/callback",
      }),
    ).toThrow(/must use HTTPS/);
  });

  it("never includes configured values in the failure message", () => {
    try {
      loadConfig({
        AUTH_MODE: "oidc",
        OIDC_CLIENT_ID: "super-secret-client",
        OIDC_ISSUER_URI: "http://id.example.com",
      });
      throw new Error("expected loadConfig to throw");
    } catch (error) {
      const message = (error as Error).message;
      expect(message).not.toContain("super-secret-client");
      expect(message).not.toContain("id.example.com");
    }
  });

  it("builds a provider-neutral OIDC configuration in production", () => {
    const config = loadConfig({
      AUTH_MODE: "oidc",
      JWT_AUDIENCE: "grocery-api",
      NODE_ENV: "production",
      OIDC_CLIENT_ID: "grocery-storefront",
      OIDC_ISSUER_URI: "https://id.example.com/realms/grocery/",
      OIDC_REDIRECT_URI: "https://storefront.test/api/auth/callback",
      PUBLIC_ORIGIN: "https://storefront.test",
    });
    expect(config.auth.mode).toBe("oidc");
    expect(config.auth.oidc?.issuer).toBe(
      "https://id.example.com/realms/grocery",
    );
    expect(config.auth.oidc?.discoveryUrl).toBe(
      "https://id.example.com/realms/grocery/.well-known/openid-configuration",
    );
    expect(config.auth.oidc?.scopes).toEqual(["openid", "profile", "email"]);
    expect(config.jwt.issuer).toBe("https://id.example.com/realms/grocery");
    expect(config.cookieSecure).toBe(true);
  });

  it("allows a localhost HTTP issuer for local development", () => {
    const config = loadConfig({
      AUTH_MODE: "oidc",
      OIDC_CLIENT_ID: "client",
      OIDC_ISSUER_URI: "http://localhost:8080/realms/grocery",
      OIDC_REDIRECT_URI: "http://localhost:3000/api/auth/callback",
    });
    expect(config.auth.mode).toBe("oidc");
  });
});
