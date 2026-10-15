import { afterEach, describe, expect, it, vi } from "vitest";
import { createPaymentSession, submitCheckout } from "./checkout-client";
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
describe("submitCheckout", () => {
  it("submits the real cart and key", async () => {
    const f = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify(order), { status: 201 }));
    vi.stubGlobal("fetch", f);
    await expect(
      submitCheckout({ cartId: 2, idempotencyKey: "same" }),
    ).resolves.toEqual(order);
    expect(f.mock.calls[0][1].body).toBe(
      '{"cartId":2,"idempotencyKey":"same"}',
    );
  });

  describe("createPaymentSession", () => {
    it("requests a hosted checkout URL for the order", async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            checkoutUrl: "https://checkout.stripe.com/session",
          }),
        ),
      );
      vi.stubGlobal("fetch", fetchMock);

      await expect(createPaymentSession(42)).resolves.toEqual({
        checkoutUrl: "https://checkout.stripe.com/session",
      });
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/customer/orders/42/payment-session",
        { method: "POST" },
      );
    });
  });
  it.each([400, 401, 403, 404, 409, 422, 503])(
    "maps status %s",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response("{}", { status })),
      );
      await expect(
        submitCheckout({ cartId: 2, idempotencyKey: "same" }),
      ).rejects.toHaveProperty("name", "ApplicationError");
    },
  );
});
