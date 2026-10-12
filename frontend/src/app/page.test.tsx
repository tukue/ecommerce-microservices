import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import Home from "./page";
afterEach(() => vi.unstubAllGlobals());
it("uses the active public catalogue for the legacy home export", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]")));
  render(
    <MemoryRouter>
      <Home />
    </MemoryRouter>,
  );
  expect(screen.getByRole("heading", { name: "Products" })).toBeVisible();
  expect(await screen.findByText(/No products/i)).toBeVisible();
});
