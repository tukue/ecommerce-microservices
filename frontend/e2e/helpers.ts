import { expect, type Page, type Route } from "@playwright/test";

const customer = { userId: "e2e-customer", email: "e2e@example.test" };
const product = {
  id: 1,
  name: "Apple",
  description: "Crisp apples for snacks and baking.",
  price: 2.29,
  currency: "SEK",
  available: true,
  stockQuantity: 100,
};

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

export async function mockApi(page: Page) {
  let authenticated = false;
  let cart: {
    id: number;
    status: "OPEN";
    items: Array<Record<string, number | string>>;
  } | null = null;
  let nextItemId = 1;
  let nextOrderId = 1;
  let receiptReads = 0;
  const orders: Array<Record<string, unknown>> = [];

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;

    // The broad glob also matches Vite source modules in directories named
    // `api` (for example, /src/features/auth/api/auth-api.ts). Let those
    // requests reach the dev server and only mock the BFF's /api namespace.
    if (!path.startsWith("/api/")) {
      await route.continue();
      return;
    }

    if (path === "/api/auth/config") {
      await json(route, 200, { mode: "demo" });
      return;
    }
    if (path === "/api/auth/login" && method === "POST") {
      authenticated = true;
      await json(route, 200, customer);
      return;
    }
    if (path === "/api/auth/logout" && method === "POST") {
      authenticated = false;
      await route.fulfill({ status: 204 });
      return;
    }
    if (path === "/api/auth/me" && method === "GET") {
      await json(
        route,
        authenticated ? 200 : 401,
        authenticated ? customer : {},
      );
      return;
    }
    if (path === "/api/catalog/products" && method === "GET") {
      await json(route, 200, [product]);
      return;
    }
    if (path === "/api/catalog/products/search" && method === "GET") {
      await json(route, 200, [product]);
      return;
    }
    if (path === "/api/catalog/products/1" && method === "GET") {
      await json(route, 200, product);
      return;
    }
    if (!authenticated && path.startsWith("/api/customer/")) {
      await json(route, 401, { status: 401, message: "Not authenticated" });
      return;
    }
    if (path === "/api/customer/cart" && method === "GET") {
      await json(route, cart ? 200 : 404, cart ?? { status: 404 });
      return;
    }
    if (path === "/api/customer/cart" && method === "POST") {
      cart = { id: 42, status: "OPEN", items: [] };
      await json(route, 201, cart);
      return;
    }
    if (path === "/api/customer/cart/42/items" && method === "POST") {
      const input = request.postDataJSON() as {
        productId: number;
        quantity: number;
      };
      if (!cart) cart = { id: 42, status: "OPEN", items: [] };
      const existing = cart.items.find(
        (item) => item.productId === input.productId,
      );
      if (existing) {
        existing.quantity = Number(existing.quantity) + input.quantity;
      } else {
        cart.items.push({
          id: nextItemId++,
          productId: product.id,
          productName: product.name,
          price: product.price,
          quantity: input.quantity,
        });
      }
      await json(route, 200, cart);
      return;
    }
    const itemPath = path.match(/^\/api\/customer\/cart\/42\/items\/(\d+)$/);
    if (itemPath && cart && method === "PATCH") {
      const input = request.postDataJSON() as { quantity: number };
      const item = cart.items.find(
        (candidate) => candidate.id === Number(itemPath[1]),
      );
      if (item) item.quantity = input.quantity;
      await json(route, 200, cart);
      return;
    }
    if (itemPath && cart && method === "DELETE") {
      cart.items = cart.items.filter((item) => item.id !== Number(itemPath[1]));
      await json(route, 200, cart);
      return;
    }
    if (path === "/api/customer/checkout" && method === "POST" && cart) {
      const orderLines = cart.items.map((item) => ({
        productId: Number(item.productId),
        productName: String(item.productName),
        quantity: Number(item.quantity),
        unitPrice: Number(item.price),
        lineTotal: Number(
          (Number(item.price) * Number(item.quantity)).toFixed(2),
        ),
      }));
      const order = {
        id: nextOrderId++,
        userId: customer.userId,
        cartId: cart.id,
        status: "PENDING",
        orderDate: new Date().toISOString().slice(0, 19),
        orderLines,
        total: Number(
          orderLines.reduce((sum, line) => sum + line.lineTotal, 0).toFixed(2),
        ),
      };
      orders.unshift(order);
      cart = null;
      await json(route, 201, order);
      return;
    }
    if (path === "/api/customer/orders" && method === "GET") {
      await json(route, 200, orders);
      return;
    }
    if (
      /^\/api\/customer\/ledger\/orders\/\d+\/receipt$/.test(path) &&
      method === "GET"
    ) {
      receiptReads++;
      await json(
        route,
        receiptReads === 1 ? 202 : 200,
        receiptReads === 1
          ? { status: "pending" }
          : { status: "ready", content: "Grove receipt — Apple — Total: 2.29" },
      );
      return;
    }
    const orderPath = path.match(/^\/api\/customer\/orders\/(\d+)$/);
    if (orderPath && method === "GET") {
      const order = orders.find(
        (candidate) => candidate.id === Number(orderPath[1]),
      );
      await json(route, order ? 200 : 404, order ?? { status: 404 });
      return;
    }
    await json(route, 404, {
      status: 404,
      message: `Unmocked API request: ${method} ${path}`,
    });
  });
}

export async function signIn(page: Page) {
  await mockApi(page);
  await page.goto("/login");
  await page.getByLabel("Username").fill("demo-user");
  await page.getByLabel("Password").fill("test-password");
  const loginResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/auth/login") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign In" }).click();
  const response = await loginResponse;
  expect(response.status(), await response.text()).toBe(200);
  await expect(page).not.toHaveURL(/\/login$/);
}

export async function addProductToCart(page: Page) {
  await page.goto("/products");
  await expect(page.getByRole("heading", { name: "Products" })).toBeVisible();
  const productLink = page.getByRole("link", { name: "View product" }).first();
  await expect(productLink).toBeVisible();
  await productLink.click();
  const addButton = page.getByRole("button", { name: "Add to Cart" });
  await expect(addButton).toBeEnabled();
  const addResponse = page.waitForResponse(
    (response) =>
      /\/api\/customer\/cart\/\d+\/items$/.test(response.url()) &&
      response.request().method() === "POST",
  );
  await addButton.click();
  const response = await addResponse;
  expect(response.status(), await response.text()).toBe(200);
}

export async function placeOrder(page: Page) {
  await signIn(page);
  await addProductToCart(page);
  await page.goto("/checkout");
  await expect(page.getByRole("heading", { name: "Checkout" })).toBeVisible();
  const checkoutResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/customer/checkout") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Submit order" }).click();
  const response = await checkoutResponse;
  expect(response.status(), await response.text()).toBe(201);
  await expect(page).toHaveURL(/\/confirmation\/\d+$/);
}
