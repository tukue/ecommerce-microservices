import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchProduct, fetchProducts, searchProducts } from "./product-client";

const product = {
  id: 1,
  name: "Apple",
  description: "Fresh",
  price: 1,
  currency: "SEK",
  available: true,
};
afterEach(() => vi.unstubAllGlobals());
describe("product client", () => {
  it("lists and URL-encodes search", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify([product]), { status: 200 }),
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    await expect(fetchProducts()).resolves.toEqual([product]);
    await searchProducts("red & green");
    expect(fetchMock).toHaveBeenLastCalledWith(
      expect.stringContaining("red%20%26%20green"),
      expect.anything(),
    );
  });
  it("loads details and rejects malformed payloads", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify(product), { status: 200 }),
        ),
    );
    await expect(fetchProduct(1)).resolves.toEqual(product);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 200 })),
    );
    await expect(fetchProduct(1)).rejects.toThrow();
  });
  it("maps not found", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 404 })),
    );
    await expect(fetchProduct(9)).rejects.toMatchObject({
      status: 404,
    });
  });
});
