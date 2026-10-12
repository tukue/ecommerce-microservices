import { describe, expect, it } from "vitest";
import { MemorySessionStore } from "../session-store";

describe("SessionStore", () => {
  const input = {
    email: "shopper@example.com",
    expiresAt: Date.now() + 60_000,
    jwt: "secret",
    userId: "shopper",
  };
  it("creates an opaque unique id and looks up an active session", async () => {
    const store = new MemorySessionStore();
    const first = await store.create(input);
    const second = await store.create(input);
    expect(first.id).not.toBe(second.id);
    expect(first.id).not.toContain(input.jwt);
    expect(await store.get(first.id, input.expiresAt - 1)).toEqual(first);
  });
  it("expires and deletes sessions", async () => {
    const store = new MemorySessionStore();
    const session = await store.create(input);
    expect(await store.get(session.id, input.expiresAt)).toBeNull();
    expect(await store.delete(session.id)).toBe(false);
  });
});
