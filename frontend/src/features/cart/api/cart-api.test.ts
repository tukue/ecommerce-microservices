import { describe, expect, it } from "vitest";
import { createApplicationError } from "@/shared/errors/application-error";
import type {
  ServerHttpClient,
  ServerHttpRequest,
} from "@/shared/http/server-http-client";
import { createCartApi } from "./cart-api";

const context = { authorization: "Bearer test-token" };
const cart = { currency: null, id: 42, items: [], status: "OPEN" };

describe("CartApi", () => {
  it("retrieves the current cart", async () => {
    const http: ServerHttpClient = {
      request: async <TResponse>() => cart as TResponse,
    };
    await expect(createCartApi(http).getCurrent(context)).resolves.toEqual(
      cart,
    );
  });

  it("adds a valid quantity to the current cart", async () => {
    const requests: unknown[] = [];
    const http: ServerHttpClient = {
      request: async <TResponse>(request: ServerHttpRequest) => {
        requests.push(request);
        return cart as TResponse;
      },
    };
    await expect(
      createCartApi(http).addProduct(12, 2, context),
    ).resolves.toEqual(cart);
    expect(requests).toHaveLength(2);
  });

  it("rejects an invalid quantity before making a request", async () => {
    const http: ServerHttpClient = {
      request: async <TResponse>() => cart as TResponse,
    };
    await expect(
      createCartApi(http).addProduct(12, 0, context),
    ).rejects.toThrow();
  });

  it("preserves cart-service unavailability", async () => {
    const error = createApplicationError("service-unavailable");
    const http: ServerHttpClient = {
      request: async <TResponse>() => Promise.reject(error) as TResponse,
    };
    await expect(createCartApi(http).getCurrent(context)).rejects.toBe(error);
  });
});
