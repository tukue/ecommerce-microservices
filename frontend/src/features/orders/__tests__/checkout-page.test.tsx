import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "../../auth/components/auth-context";
import type { CartAdapter, CartDTO } from "../../cart/api/cart-adapter";
import { CartProvider } from "../../cart/components/cart-context";
import { CheckoutPage } from "../components/checkout-page";
import { ConfirmationPage } from "../components/confirmation-page";

function createMockAdapter(overrides: Partial<CartAdapter> = {}): CartAdapter {
  return {
    getCurrentCart: vi.fn(),
    createCart: vi.fn(),
    addItem: vi.fn(),
    updateItemQuantity: vi.fn(),
    removeItem: vi.fn(),
    ...overrides,
  } as unknown as CartAdapter;
}

const mockCart: CartDTO = {
  id: 1,
  status: "OPEN",
  items: [
    { id: 10, productId: 42, productName: "Apple", price: 2.5, quantity: 3 },
  ],
};

const mockOrder = {
  cartId: 1,
  id: 100,
  orderDate: "2026-09-23T10:00:00Z",
  orderLines: [
    {
      lineTotal: 7.5,
      productId: 42,
      productName: "Apple",
      quantity: 3,
      unitPrice: 2.5,
    },
  ],
  status: "PENDING",
  total: 7.5,
  userId: "u1",
};

function stubFetch(handler: (input: unknown) => Promise<Response>) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((input: unknown) => handler(input)),
  );
}

function renderCheckout(adapter: CartAdapter, redirectToPayment = vi.fn()) {
  return render(
    <MemoryRouter initialEntries={["/checkout"]}>
      <AuthProvider>
        <CartProvider adapter={adapter}>
          <Routes>
            <Route
              path="/checkout"
              element={<CheckoutPage redirectToPayment={redirectToPayment} />}
            />
            <Route
              path="/confirmation/:orderId"
              element={<ConfirmationPage />}
            />
          </Routes>
        </CartProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("CheckoutPage", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows the cart summary and submits the order", async () => {
    stubFetch((input) => {
      if (String(input).includes("/api/auth/me")) {
        return Promise.resolve(
          new Response(JSON.stringify({ userId: "u1", email: "a@b.c" })),
        );
      }
      if (String(input).includes("/payment-session")) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ checkoutUrl: "https://checkout.stripe.com/test" }),
          ),
        );
      }
      return Promise.resolve(new Response(JSON.stringify(mockOrder)));
    });
    const redirectToPayment = vi.fn();
    const adapter = createMockAdapter({
      getCurrentCart: vi.fn().mockResolvedValue(mockCart),
    });
    renderCheckout(adapter, redirectToPayment);

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /continue to payment/i }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Cart total: 7.50 SEK")).toBeInTheDocument();

    const submitButton = screen.getByRole("button", {
      name: /continue to payment/i,
    });
    await waitFor(() => expect(submitButton).toBeEnabled());
    fireEvent.click(submitButton);

    await waitFor(() =>
      expect(redirectToPayment).toHaveBeenCalledWith(
        "https://checkout.stripe.com/test",
      ),
    );
    expect(fetch).toHaveBeenCalledWith(
      "/api/customer/checkout",
      expect.objectContaining({
        body: expect.stringMatching(/"idempotencyKey":"[0-9a-f-]{36}"/),
        method: "POST",
      }),
    );
    expect(fetch).toHaveBeenCalledWith(
      "/api/customer/orders/100/payment-session",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("shows an empty state when the cart has no items", async () => {
    stubFetch((input) => {
      if (String(input).includes("/api/auth/me")) {
        return Promise.resolve(
          new Response(JSON.stringify({ userId: "u1", email: "a@b.c" })),
        );
      }
      return Promise.reject(new Error("should not fetch"));
    });
    const adapter = createMockAdapter({
      getCurrentCart: vi.fn().mockResolvedValue({ ...mockCart, items: [] }),
    });
    renderCheckout(adapter);

    await waitFor(() =>
      expect(screen.getByText(/your cart is empty/i)).toBeInTheDocument(),
    );
    expect(
      screen.queryByRole("button", { name: /continue to payment/i }),
    ).not.toBeInTheDocument();
  });
});
