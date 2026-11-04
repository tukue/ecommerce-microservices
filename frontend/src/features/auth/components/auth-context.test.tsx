import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useSession } from "./auth-context";
afterEach(() => vi.unstubAllGlobals());
function Probe() {
  const { loading, session, login } = useSession();
  return (
    <div>
      <span>{loading ? "loading" : (session?.email ?? "signed out")}</span>
      <button onClick={() => void login("new", "password")}>Sign in</button>
    </div>
  );
}
describe("AuthProvider", () => {
  it("loads an existing session", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ userId: "u", email: "u@example.com" }),
            { status: 200 },
          ),
        ),
    );
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(screen.getByText("loading")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText("u@example.com")).toBeInTheDocument(),
    );
  });
  it("settles signed out after an expired session", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 401 })),
    );
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() =>
      expect(screen.getByText("signed out")).toBeInTheDocument(),
    );
  });
});

function sessionResponse(email = "u@example.com") {
  return new Response(JSON.stringify({ userId: email, email }), {
    status: 200,
  });
}

describe("session recovery when returning to a tab", () => {
  it("hides stale identity, coalesces activation events, and detects sign-out", async () => {
    let resolve!: (response: Response) => void;
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(sessionResponse())
      .mockImplementationOnce(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      );
    vi.stubGlobal("fetch", fetch);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText("u@example.com");
    fireEvent.focus(window);
    fireEvent(document, new Event("visibilitychange"));
    expect(screen.queryByText("u@example.com")).not.toBeInTheDocument();
    expect(screen.getByText("loading")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(2);
    await act(async () => resolve(new Response("{}", { status: 401 })));
    expect(screen.getByText("signed out")).toBeInTheDocument();
  });

  it("loads the new account when the shared session cookie changes", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(sessionResponse())
        .mockResolvedValueOnce(sessionResponse("other@example.com")),
    );
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText("u@example.com");
    fireEvent.focus(window);
    await screen.findByText("other@example.com");
    expect(screen.queryByText("u@example.com")).not.toBeInTheDocument();
  });

  it("clears stale identity on network failure and recovers on the next focus", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(sessionResponse())
      .mockRejectedValueOnce(new TypeError("Network unavailable"))
      .mockResolvedValueOnce(sessionResponse());
    vi.stubGlobal("fetch", fetch);
    const { unmount } = render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText("u@example.com");
    fireEvent.focus(window);
    await screen.findByText("signed out");
    fireEvent.focus(window);
    await screen.findByText("u@example.com");
    unmount();
    fireEvent.focus(window);
    expect(fetch).toHaveBeenCalledTimes(3);
  });
});

it("ignores a stale session check after a newer sign-in", async () => {
  let resolve!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(sessionResponse())
      .mockImplementationOnce(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      )
      .mockResolvedValueOnce(sessionResponse("new@example.com")),
  );
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
  await screen.findByText("u@example.com");
  fireEvent.focus(window);
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByText("new@example.com");
  await act(async () => resolve(sessionResponse()));
  expect(screen.getByText("new@example.com")).toBeInTheDocument();
});
