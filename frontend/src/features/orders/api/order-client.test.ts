import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchOrders } from "./order-client";
const order = {
  id: 1,
  userId: "u",
  cartId: 2,
  status: "PENDING",
  orderDate: "2026-09-30T10:00:00",
  total: 2,
  orderLines: [
    {
      productId: 3,
      productName: "Apple",
      unitPrice: 1,
      quantity: 2,
      lineTotal: 2,
    },
  ],
};
afterEach(() => vi.unstubAllGlobals());
describe("fetchOrders", () => {
  it("validates lists and accepts empty history", async () => {
    const f = vi
      .fn()
      .mockImplementationOnce(() =>
        Promise.resolve(new Response(JSON.stringify([order]), { status: 200 })),
      )
      .mockImplementationOnce(() =>
        Promise.resolve(new Response("[]", { status: 200 })),
      );
    vi.stubGlobal("fetch", f);
    await expect(fetchOrders()).resolves.toEqual([order]);
    await expect(fetchOrders()).resolves.toEqual([]);
  });
  it.each([401, 502])("preserves status %s", async (status) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status })),
    );
    await expect(fetchOrders()).rejects.toMatchObject({
      status,
    });
  });
  it("rejects malformed arrays", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response('[{"id":"bad"}]', { status: 200 })),
    );
    await expect(fetchOrders()).rejects.toThrow();
  });
});
