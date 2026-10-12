import { afterEach, describe, expect, it, vi } from "vitest";
import { CartClient } from "./cart-client";
const cart = { id: 1, status: "OPEN", items: [] };
afterEach(() => vi.unstubAllGlobals());
describe("CartClient", () => {
  it("gets, creates, and mutates using only allowed request fields", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(new Response(JSON.stringify(cart), { status: 200 })),
      );
    vi.stubGlobal("fetch", fetchMock);
    const client = new CartClient();
    await client.getCurrentCart();
    await client.createCart();
    await client.addItem(1, 2, 3);
    await client.updateItemQuantity(1, 4, 2);
    await client.removeItem(1, 4);
    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(fetchMock.mock.calls[2][1].body).toBe(
      '{"productId":2,"quantity":3}',
    );
  });
  it("maps 404 current cart to null and preserves other statuses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 404 })),
    );
    await expect(new CartClient().getCurrentCart()).resolves.toBeNull();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: "conflict" }), {
          status: 409,
        }),
      ),
    );
    await expect(new CartClient().createCart()).rejects.toMatchObject({
      status: 409,
    });
  });
});
