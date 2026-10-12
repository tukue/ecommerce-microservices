// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "./auth-context";
import { LoginPage } from "./login-page";

function stubFetch(mode: "oidc" | "password") {
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/api/auth/config")) {
        return Promise.resolve(
          new Response(JSON.stringify({ mode }), { status: 200 }),
        );
      }
      return Promise.resolve(new Response("{}", { status: 401 }));
    }),
  );
}

function renderLogin() {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("LoginPage", () => {
  it("offers provider sign-in in OIDC mode", async () => {
    stubFetch("oidc");
    renderLogin();
    expect(
      await screen.findByRole("button", { name: /continue to sign in/i }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument();
  });

  it("keeps the development form in password mode", async () => {
    stubFetch("password");
    renderLogin();
    expect(await screen.findByLabelText(/username/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /continue to sign in/i }),
    ).not.toBeInTheDocument();
  });
});
