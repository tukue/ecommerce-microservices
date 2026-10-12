import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { createApplicationError } from "../../../shared/errors/application-error";

import { CheckoutForm } from "../components/checkout-form";

const cart = {
  id: 42,
  items: [
    {
      lineTotal: 29.9,
      productId: 12,
      productName: "Apples",
      quantity: 1,
      unitPrice: 29.9,
    },
  ],
  total: 29.9,
};
const order = {
  cartId: 42,
  id: 101,
  orderDate: "2026-09-17T14:45:00",
  orderLines: [],
  status: "PENDING" as const,
  total: 29.9,
};

describe("CheckoutForm", () => {
  it("renders cart summary with items and total", () => {
    render(
      <CheckoutForm cart={cart} onConfirmed={vi.fn()} submitOrder={vi.fn()} />,
    );
    expect(screen.getByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText("Cart total: 29.90 SEK")).toBeInTheDocument();
    expect(screen.getByLabelText("Cart summary")).toHaveTextContent(
      "Apples x 1",
    );
  });

  it("confirms only with the backend order ID", async () => {
    const user = userEvent.setup();
    const onConfirmed = vi.fn();
    render(
      <CheckoutForm
        cart={cart}
        onConfirmed={onConfirmed}
        submitOrder={vi.fn().mockResolvedValue(order)}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: "Continue to payment" }),
    );
    expect(onConfirmed).toHaveBeenCalledWith(order);
  });

  it("preserves the generated retry key and shows safe failures", async () => {
    const user = userEvent.setup();
    render(
      <CheckoutForm
        cart={cart}
        onConfirmed={vi.fn()}
        submitOrder={vi
          .fn()
          .mockRejectedValue(createApplicationError("timeout"))}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: "Continue to payment" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "The request took too long",
    );
  });

  it("allows only one active submission", async () => {
    const user = userEvent.setup();
    let resolveOrder: ((value: typeof order) => void) | undefined;
    const submitOrder = vi.fn(
      () =>
        new Promise<typeof order>((resolve) => {
          resolveOrder = resolve;
        }),
    );
    render(
      <CheckoutForm
        cart={cart}
        onConfirmed={vi.fn()}
        submitOrder={submitOrder}
      />,
    );

    const submitButton = screen.getByRole("button", {
      name: "Continue to payment",
    });
    await user.click(submitButton);
    expect(submitButton).toBeDisabled();
    await user.click(submitButton);
    expect(submitOrder).toHaveBeenCalledTimes(1);
    resolveOrder?.(order);
  });
});
