import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ProductList } from "./product-list";

const product = {
  id: 1,
  name: "Apple",
  description: "Fresh",
  price: 1,
  currency: "SEK",
  available: true,
};
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("product journey", () => {
  it("renders products and debounces search", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(
          async () => new Response(JSON.stringify([product]), { status: 200 }),
        ),
    );
    render(
      <MemoryRouter initialEntries={["/products"]}>
        <ProductList />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Apple")).toBeInTheDocument();
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "r" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    fireEvent.change(screen.getByRole("searchbox"), {
      target: { value: "re" },
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    fireEvent.change(screen.getByRole("searchbox"), {
      target: { value: "red" },
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(299);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(vi.mocked(fetch).mock.calls[1][0]).toContain("red");
    expect(screen.getByText("Apple")).toBeVisible();
  });
  it("shows empty and error states", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("[]", { status: 200 })),
    );
    const { unmount } = render(
      <MemoryRouter>
        <ProductList />
      </MemoryRouter>,
    );
    expect(await screen.findByText("No products found.")).toBeInTheDocument();
    unmount();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    render(
      <MemoryRouter>
        <ProductList />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load",
    );
  });
});
