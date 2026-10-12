// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import type { RedisClientType } from "redis";
import {
  RedisAuthRequestStore,
  type AuthorizationRequest,
} from "../auth-request-store";

const input = {
  codeVerifier: "verifier",
  expiresAt: Date.now() + 60_000,
  nonce: "nonce",
  returnTo: "/cart",
};

function fakeClient(set: ReturnType<typeof vi.fn>) {
  return { set } as unknown as RedisClientType;
}

describe("RedisAuthRequestStore", () => {
  it("uses the first state whose SET NX succeeds", async () => {
    const set = vi.fn().mockResolvedValueOnce("OK");
    const store = RedisAuthRequestStore.fromClient(fakeClient(set));
    const request = await store.create(input);
    expect(set).toHaveBeenCalledTimes(1);
    expect(request.state).toBeTruthy();
  });

  it("regenerates the state when SET NX reports a collision (CWE-362)", async () => {
    const set = vi.fn().mockResolvedValueOnce(null).mockResolvedValueOnce("OK");
    const store = RedisAuthRequestStore.fromClient(fakeClient(set));
    const request: AuthorizationRequest = await store.create(input);
    expect(set).toHaveBeenCalledTimes(2);
    const firstKey = set.mock.calls[0][0] as string;
    const secondKey = set.mock.calls[1][0] as string;
    expect(firstKey).not.toBe(secondKey);
    expect(secondKey).toBe(`grocery:authreq:${request.state}`);
  });

  it("fails instead of returning an unstorable request", async () => {
    const set = vi.fn().mockResolvedValue(null);
    const store = RedisAuthRequestStore.fromClient(fakeClient(set));
    await expect(store.create(input)).rejects.toThrow(
      "Failed to create authorization request",
    );
    expect(set).toHaveBeenCalledTimes(3);
  });
});
