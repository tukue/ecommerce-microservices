import { describe, expect, it } from "vitest";
import { resolveService } from "../proxy";

const urls = {
  cart: "http://cart",
  order: "http://order",
  product: "http://product",
  ledger: "http://ledger",
};
const operations = [
  ["GET", "/api/customer/ledger", "ledger", "/api/customer/ledger"],
  [
    "GET",
    "/api/customer/ledger/orders/7/receipt",
    "ledger",
    "/api/customer/ledger/orders/7/receipt",
  ],
  ["GET", "/api/catalog/products", "product", "/products"],
  [
    "GET",
    "/api/catalog/products/search?name=apple",
    "product",
    "/products/search",
  ],
  ["GET", "/api/catalog/products/1", "product", "/products/1"],
  ["GET", "/api/customer/cart", "cart", "/api/customer/cart"],
  ["POST", "/api/customer/cart", "cart", "/api/customer/cart"],
  ["POST", "/api/customer/cart/1/items", "cart", "/api/customer/cart/1/items"],
  [
    "PATCH",
    "/api/customer/cart/1/items/2",
    "cart",
    "/api/customer/cart/1/items/2",
  ],
  [
    "DELETE",
    "/api/customer/cart/1/items/2",
    "cart",
    "/api/customer/cart/1/items/2",
  ],
  ["POST", "/api/customer/checkout", "order", "/api/customer/checkout"],
  ["GET", "/api/customer/orders", "order", "/api/customer/orders"],
  ["GET", "/api/customer/orders/1", "order", "/api/customer/orders/1"],
  [
    "POST",
    "/api/customer/orders/1/payment-session",
    "order",
    "/api/customer/orders/1/payment-session",
  ],
] as const;

describe("resolveService", () => {
  it.each(operations)("allows %s %s", (method, path, service, upstreamPath) => {
    expect(resolveService(method, path, urls)).toMatchObject({
      service,
      upstreamPath,
    });
  });
  it.each([
    ["POST", "/api/catalog/products"],
    ["GET", "/api/customer/cart/1/items"],
    ["GET", "/api/admin/users"],
  ])("rejects %s %s", (method, path) => {
    expect(resolveService(method, path, urls)).toBeNull();
  });
});
