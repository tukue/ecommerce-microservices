import { randomBytes, randomUUID } from "node:crypto";
import { createClient, type RedisClientType } from "redis";

/**
 * One in-flight OIDC authorization request. The `state` value is both the
 * browser-visible request identity and the storage key; `nonce` and
 * `codeVerifier` never leave the server.
 */
export interface AuthorizationRequest {
  codeVerifier: string;
  expiresAt: number;
  nonce: string;
  returnTo: string;
  state: string;
}

export type NewAuthorizationRequest = Omit<AuthorizationRequest, "state">;

export interface AuthRequestStore {
  create(input: NewAuthorizationRequest): Promise<AuthorizationRequest>;
  /** Returns the request exactly once, then deletes it. */
  consume(
    state: string | undefined,
    now?: number,
  ): Promise<AuthorizationRequest | null>;
}

export function generateOpaqueValue(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export class RedisAuthRequestStore implements AuthRequestStore {
  private constructor(
    private readonly client: RedisClientType,
    private readonly prefix = "grocery:authreq:",
  ) {}

  static async connect(url: string): Promise<RedisAuthRequestStore> {
    const client = createClient({ url });
    client.on("error", (error) =>
      // eslint-disable-next-line no-console
      console.error("Redis auth request store error", error),
    );
    await client.connect();
    return new RedisAuthRequestStore(client as RedisClientType);
  }

  /** Visible for tests: build a store over an already-connected client. */
  static fromClient(
    client: RedisClientType,
    prefix?: string,
  ): RedisAuthRequestStore {
    return new RedisAuthRequestStore(client, prefix);
  }

  async create(input: NewAuthorizationRequest): Promise<AuthorizationRequest> {
    const ttl = Math.max(1, Math.ceil((input.expiresAt - Date.now()) / 1000));
    // SET NX returns "OK" only when the key did not exist. If the random state
    // collides with a live request, regenerate instead of silently returning a
    // request that could never be consumed (CWE-362).
    for (let attempt = 0; attempt < 3; attempt++) {
      const request = { ...input, state: generateOpaqueValue() };
      const stored = await this.client.set(
        this.prefix + request.state,
        JSON.stringify(request),
        { EX: ttl, NX: true },
      );
      if (stored === "OK") return request;
    }
    throw new Error("Failed to create authorization request");
  }

  async consume(state: string | undefined, now = Date.now()) {
    if (!state) return null;
    const raw = await this.client.getDel(this.prefix + state);
    if (!raw) return null;
    try {
      const request = JSON.parse(raw) as AuthorizationRequest;
      if (request.state !== state || request.expiresAt <= now) return null;
      return request;
    } catch {
      return null;
    }
  }
}

/** Test-only adapter. Runtime code uses RedisAuthRequestStore. */
export class MemoryAuthRequestStore implements AuthRequestStore {
  private readonly requests = new Map<string, AuthorizationRequest>();

  async create(input: NewAuthorizationRequest): Promise<AuthorizationRequest> {
    const request = { ...input, state: generateOpaqueValue() };
    this.requests.set(request.state, request);
    return request;
  }

  async consume(state: string | undefined, now = Date.now()) {
    if (!state) return null;
    const request = this.requests.get(state);
    if (!request) return null;
    this.requests.delete(state);
    if (request.expiresAt <= now) return null;
    return request;
  }
}

// Fast, opaque identifiers are also used for logging correlation where needed.
export const newCorrelationId = randomUUID;
