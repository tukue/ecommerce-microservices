// @vitest-environment node
import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { RedisSessionStore } from "../session-store.js";
import { newLogin } from "../oidc.js";

const redis = process.env.TEST_REDIS_URL;
(redis ? describe : describe.skip)("real Redis sessions", () => {
  let first: RedisSessionStore;
  let second: RedisSessionStore;
  let otherEnvironment: RedisSessionStore;
  const namespace = `grove:test:${randomUUID()}`;
  beforeAll(async () => {
    first = await RedisSessionStore.connect(redis!, namespace);
    second = await RedisSessionStore.connect(redis!, namespace);
    otherEnvironment = await RedisSessionStore.connect(
      redis!,
      namespace + ":other",
    );
  });
  afterAll(async () => {
    await Promise.all([
      first?.close(),
      second?.close(),
      otherEnvironment?.close(),
    ]);
  });
  const identity = () => ({
    email: "test@example.test",
    userId: "test-identity",
    jwt: "server-only-token",
    expiresAt: Date.now() + 60_000,
  });
  it("shares sessions across instances and retains them after connection restart", async () => {
    const session = await first.create(identity());
    expect(await second.get(session.id)).toEqual(session);
    await first.close();
    first = await RedisSessionStore.connect(redis!, namespace);
    expect(await first.get(session.id)).toEqual(session);
    expect(await otherEnvironment.get(session.id)).toBeNull();
    await second.delete(session.id);
    expect(await first.get(session.id)).toBeNull();
  });
  it("expires sessions and rejects malformed session identifiers", async () => {
    const session = await first.create(identity());
    expect(await second.get(session.id, session.expiresAt)).toBeNull();
    expect(await first.get(session.id)).toBeNull();
    expect(await first.get("invalid-key")).toBeNull();
  });
  it("consumes login transactions once under concurrent callbacks", async () => {
    const login = newLogin("/cart");
    const id = await first.saveLogin(login);
    const results = await Promise.all([
      first.takeLogin(id),
      second.takeLogin(id),
    ]);
    expect(results.filter(Boolean)).toEqual([login]);
    expect(await first.takeLogin(id)).toBeNull();
  });
  it("shares login throttling across instances and isolates environments", async () => {
    const key = randomUUID();
    expect(await first.allowLogin(key, 2, 1)).toBe(true);
    expect(await second.allowLogin(key, 2, 1)).toBe(true);
    expect(await first.allowLogin(key, 2, 1)).toBe(false);
    expect(await otherEnvironment.allowLogin(key, 2, 1)).toBe(true);
  });
  it("rejects expired identities before storing a session", async () => {
    await expect(
      first.create({ ...identity(), expiresAt: Date.now() - 1 }),
    ).rejects.toThrow("expired");
    await first.ping();
  });
});
