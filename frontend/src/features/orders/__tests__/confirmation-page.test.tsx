import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, it, expect, vi } from "vitest";
import { ConfirmationPage } from "../components/confirmation-page";
const order = {
  id: 42,
  cartId: 1,
  userId: "customer",
  status: "COMPLETED",
  orderDate: "2026-10-06T10:00:00",
  total: 2,
  orderLines: [
    {
      productId: 1,
      productName: "Apple",
      quantity: 1,
      unitPrice: 2,
      lineTotal: 2,
    },
  ],
};
afterEach(() => vi.unstubAllGlobals());
function renderWithRoute() {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockImplementation((path: string) =>
        Promise.resolve(
          new Response(
            JSON.stringify(
              path.includes("/receipt") ? { status: "pending" } : order,
            ),
          ),
        ),
      ),
  );
  return render(
    <MemoryRouter initialEntries={["/confirmation/42"]}>
      <Routes>
        <Route path="/confirmation/:orderId" element={<ConfirmationPage />} />
      </Routes>
    </MemoryRouter>,
  );
}
describe("ConfirmationPage", () => {
  it("claims confirmation only after the persisted order loads", async () => {
    renderWithRoute();
    expect(
      screen.queryByRole("heading", { name: "Order Confirmed" }),
    ).not.toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "Order Confirmed" }),
    ).toBeInTheDocument();
  });
  it("shows the persisted order identifier", async () => {
    renderWithRoute();
    expect(await screen.findByText("Order ID: 42")).toBeInTheDocument();
  });
  it("shows a thank-you message for a persisted order", async () => {
    renderWithRoute();
    expect(
      await screen.findByText("Thank you for your order!"),
    ).toHaveAttribute("role", "status");
  });
});
