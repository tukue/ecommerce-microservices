import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ConfirmationPage } from "./confirmation-page";
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
function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/confirmation/1"]}>
      <Routes>
        <Route path="/confirmation/:orderId" element={<ConfirmationPage />} />
      </Routes>
    </MemoryRouter>,
  );
}
describe("persisted confirmation", () => {
  it("loads and renders persisted details", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify(order), { status: 200 }),
        ),
    );
    renderPage();
    expect(await screen.findByText(/Apple × 2/)).toBeInTheDocument();
    expect(screen.getByText("Total: 2.00 SEK")).toBeInTheDocument();
  });
  it("renders missing and ownership failures without data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 404 })),
    );
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("not found");
    expect(screen.queryByText(/Apple/)).not.toBeInTheDocument();
  });
});
