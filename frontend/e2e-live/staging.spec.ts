import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("public catalogue, product deep link and signed-out access", async ({
  page,
  request,
}) => {
  const response = await request.get("/api/catalog/products");
  expect(response.status()).toBe(200);
  const products = await response.json();
  expect(products.length).toBeGreaterThan(0);
  const id = products[0].id;
  expect((await request.get(`/api/catalog/products/${id}`)).status()).toBe(200);
  await page.goto("/products");
  await expect(
    page.getByRole("heading", { name: "Products", exact: true }),
  ).toBeVisible();
  await page.goto(`/products/${id}`);
  await expect(page.getByRole("button", { name: "Add to Cart" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "Add to Cart" })).toBeVisible();
  expect((await request.get("/api/customer/orders")).status()).toBe(401);
  await page.goto("/cart");
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
});

test("two real identities and owned order history", async ({
  browser,
  baseURL,
}) => {
  const a = await browser.newContext({
    baseURL,
    storageState: process.env.LIVE_SESSION_A,
  });
  const b = await browser.newContext({
    baseURL,
    storageState: process.env.LIVE_SESSION_B,
  });
  try {
    const meA = await a.request.get("/api/auth/me");
    const meB = await b.request.get("/api/auth/me");
    expect(meA.status()).toBe(200);
    expect(meB.status()).toBe(200);
    expect((await meA.json()).userId).not.toBe((await meB.json()).userId);
    const history = await a.request.get("/api/customer/orders");
    expect(history.status()).toBe(200);
    const orders = await history.json();
    const page = await a.newPage();
    await page.goto("/orders");
    await expect(
      page.getByRole("heading", { name: "Order History", exact: true }),
    ).toBeVisible();
    if (orders.length) {
      const orderId = orders[0].id;
      expect(
        (await a.request.get(`/api/customer/orders/${orderId}`)).status(),
      ).toBe(200);
      expect([403, 404]).toContain(
        (await b.request.get(`/api/customer/orders/${orderId}`)).status(),
      );
      await page.goto(`/confirmation/${orderId}`);
      await expect(page.getByText(/^Total:/)).toBeVisible();
      await page.reload();
      await expect(page.getByText(/^Total:/)).toBeVisible();
    }
  } finally {
    await a.close();
    await b.close();
  }
});

test("checkout replay, persisted confirmation, receipt and foreign-order denial", async ({
  browser,
  baseURL,
}) => {
  const a = await browser.newContext({
    baseURL,
    storageState: process.env.LIVE_SESSION_A,
  });
  const b = await browser.newContext({
    baseURL,
    storageState: process.env.LIVE_SESSION_B,
  });
  try {
    const meA = await a.request.get("/api/auth/me");
    const meB = await b.request.get("/api/auth/me");
    expect(meA.status()).toBe(200);
    expect(meB.status()).toBe(200);
    expect((await meA.json()).userId).not.toBe((await meB.json()).userId);
    const history = await a.request.get("/api/customer/orders");
    expect(history.status()).toBe(200);
    const before = await history.json();
    const page = await a.newPage();
    await page.goto("/orders");
    await expect(
      page.getByRole("heading", { name: "Order History", exact: true }),
    ).toBeVisible();
    test.skip(
      process.env.LIVE_ALLOW_CHECKOUT !== "1",
      "Write journey requires explicit LIVE_ALLOW_CHECKOUT=1 and dedicated seeded accounts/product",
    );
    const productId = Number(process.env.LIVE_PRODUCT_ID);
    expect(Number.isSafeInteger(productId) && productId > 0).toBeTruthy();
    const cartResponse = await a.request.get("/api/customer/cart");
    expect([200, 404]).toContain(cartResponse.status());
    let cart = cartResponse.status() === 200 ? await cartResponse.json() : null;
    if (cart)
      expect(
        cart.items,
        "Refuse to mutate a nonempty existing customer cart",
      ).toEqual([]);
    if (!cart) {
      const created = await a.request.post("/api/customer/cart", {
        headers: { Origin: baseURL! },
      });
      expect(created.status()).toBe(201);
      cart = await created.json();
    }
    const added = await a.request.post(`/api/customer/cart/${cart.id}/items`, {
      headers: { Origin: baseURL! },
      data: { productId, quantity: 1 },
    });
    expect(added.status()).toBe(200);
    const input = { cartId: cart.id, idempotencyKey: `live-${randomUUID()}` };
    const first = await a.request.post("/api/customer/checkout", {
      headers: { Origin: baseURL! },
      data: input,
    });
    expect([200, 201]).toContain(first.status());
    const order = await first.json();
    const replay = await a.request.post("/api/customer/checkout", {
      headers: { Origin: baseURL! },
      data: input,
    });
    expect([200, 201]).toContain(replay.status());
    expect((await replay.json()).id).toBe(order.id);
    const after = await (await a.request.get("/api/customer/orders")).json();
    expect(after.length).toBe(before.length + 1);
    expect(
      after.filter((candidate: { id: number }) => candidate.id === order.id),
    ).toHaveLength(1);
    expect(
      (await a.request.get(`/api/customer/orders/${order.id}`)).status(),
    ).toBe(200);
    for (const path of [
      `/api/customer/orders/${order.id}`,
      `/api/customer/ledger/orders/${order.id}/receipt`,
    ]) {
      expect([403, 404]).toContain((await b.request.get(path)).status());
    }
    const receiptPath = `/api/customer/ledger/orders/${order.id}/receipt`;
    const initial = await a.request.get(receiptPath);
    expect([200, 202]).toContain(initial.status());
    if (initial.status() === 202)
      expect((await initial.json()).status).toBe("pending");
    await expect
      .poll(
        async () => {
          const receipt = await a.request.get(receiptPath);
          expect([200, 202]).toContain(receipt.status());
          return (await receipt.json()).status;
        },
        { timeout: 60_000, intervals: [250, 500, 1000, 2000] },
      )
      .toBe("ready");
    await page.goto(`/confirmation/${order.id}`);
    await expect(page.getByText(/^Total:/)).toBeVisible();
    await page.reload();
    await expect(page.getByText(/^Total:/)).toBeVisible();
  } finally {
    await a.close();
    await b.close();
  }
});
