import type { Session } from "../domain/session";
import { z } from "zod";

export type AuthMode = "oidc" | "password";

const sessionSchema = z.object({
  userId: z.string().min(1),
  email: z.string().min(1),
});

const authModeSchema = z.object({
  mode: z.enum(["oidc", "password"]),
});

export async function login(
  username: string,
  password: string,
): Promise<Session> {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.error || "Login failed");
  }
  return sessionSchema.parse(await res.json());
}

export async function logout(): Promise<void> {
  const response = await fetch("/api/auth/logout", { method: "POST" });
  if (!response.ok) throw new Error("Logout failed");
}

export async function getSession(): Promise<Session | null> {
  const res = await fetch("/api/auth/me");
  if (!res.ok) return null;
  return sessionSchema.parse(await res.json());
}

/** The active authentication mode, defaulting to the development form. */
export async function getAuthMode(): Promise<AuthMode> {
  try {
    const res = await fetch("/api/auth/config");
    if (!res.ok) return "password";
    return authModeSchema.parse(await res.json()).mode;
  } catch {
    return "password";
  }
}

/** Build the provider sign-in URL, preserving a safe internal return path. */
export function loginUrl(returnTo?: string): string {
  const base = "/api/auth/login";
  if (!returnTo) return base;
  return `${base}?returnTo=${encodeURIComponent(returnTo)}`;
}

/** Redirect the browser to the identity provider to begin sign-in. */
export function beginLogin(returnTo?: string): void {
  window.location.assign(loginUrl(returnTo));
}
