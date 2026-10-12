import { type FormEvent, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import { useAuthMode } from "../hooks/use-auth-mode";
import { useSession } from "./auth-context";

export function LoginPage() {
  const submittingRef = useRef(false);
  const { login, beginLogin } = useSession();
  const mode = useAuthMode();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function requestedDestination(): string {
    const destination =
      (location.state as { from?: string } | null)?.from ?? "/products";
    return destination.startsWith("/") && !destination.startsWith("//")
      ? destination
      : "/products";
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (submittingRef.current) return;
    submittingRef.current = true;
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
      navigate(requestedDestination(), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  function handleProviderSignIn() {
    beginLogin(requestedDestination());
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <p className="eyebrow">WELCOME TO GROVE</p>
        <h2>
          Access your account.
          <br />
          Manage your purchases.
        </h2>
        <p>Access your saved cart and order history.</p>
      </section>
      <section className="login-form">
        <h1>Sign In</h1>
        <p className="muted">Sign in to continue shopping.</p>
        {mode === "oidc" ? (
          <button
            className="bg-zinc-900 px-4 py-2 text-white disabled:opacity-50"
            onClick={handleProviderSignIn}
            type="button"
          >
            Continue to sign in
          </button>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div>
              <label
                className="mb-1 block text-sm font-medium"
                htmlFor="username"
              >
                Username
              </label>
              <input
                className="w-full border border-zinc-300 px-3 py-2"
                autoComplete="username"
                id="username"
                onChange={(e) => setUsername(e.target.value)}
                required
                type="text"
                value={username}
              />
            </div>
            <div>
              <label
                className="mb-1 block text-sm font-medium"
                htmlFor="password"
              >
                Password
              </label>
              <input
                className="w-full border border-zinc-300 px-3 py-2"
                autoComplete="current-password"
                id="password"
                onChange={(e) => setPassword(e.target.value)}
                required
                type="password"
                value={password}
              />
            </div>
            {error && (
              <p className="text-sm text-red-600" role="alert">
                {error}
              </p>
            )}
            <button
              className="bg-zinc-900 px-4 py-2 text-white disabled:opacity-50"
              disabled={submitting}
              type="submit"
            >
              {submitting ? "Signing in..." : "Sign In"}
            </button>
          </form>
        )}
      </section>
    </main>
  );
}
