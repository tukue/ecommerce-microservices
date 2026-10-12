import { randomUUID } from "node:crypto";
import { createClient, type RedisClientType } from "redis";
import { z } from "zod";

const sessionSchema = z.object({
  email: z.string().min(1),
  expiresAt: z.number().finite().positive(),
  id: z.string().uuid(),
  jwt: z.string().min(1),
  userId: z.string().min(1),
});
export type SessionRecord = z.infer<typeof sessionSchema>;
export type NewSession = Omit<SessionRecord, "id">;
export type LoginTransaction = {
  state: string;
  nonce: string;
  verifier: string;
  returnTo: string;
  expiresAt: number;
};
export interface SessionStore {
  create(input: NewSession): Promise<SessionRecord>;
  get(id: string | undefined, now?: number): Promise<SessionRecord | null>;
  delete(id: string | undefined): Promise<boolean>;
  saveLogin(input: LoginTransaction): Promise<string>;
  takeLogin(id: string): Promise<LoginTransaction | null>;
  allowLogin(
    key: string,
    limit?: number,
    windowSeconds?: number,
  ): Promise<boolean>;
  isOpen(): boolean;
  ping(): Promise<void>;
  close(): Promise<void>;
}
const loginSchema = z.object({
  state: z.string().min(1),
  nonce: z.string().min(1),
  verifier: z.string().min(1),
  returnTo: z.string().min(1),
  expiresAt: z.number().finite(),
});
function validId(id: string | undefined): id is string {
  return !!id && z.string().uuid().safeParse(id).success;
}
function record(input: NewSession): SessionRecord {
  const session = sessionSchema.parse({ ...input, id: randomUUID() });
  if (session.expiresAt <= Date.now())
    throw new Error("Cannot create an expired session");
  return session;
}

export class RedisSessionStore implements SessionStore {
  private constructor(
    private readonly client: RedisClientType,
    private readonly prefix: string,
  ) {}
  static async connect(
    url: string,
    namespace = "grove:development",
  ): Promise<RedisSessionStore> {
    const client = createClient({
      url,
      disableOfflineQueue: true,
      commandsQueueMaxLength: 100,
      socket: {
        connectTimeout: 5000,
        reconnectStrategy: (retries) =>
          retries > 3
            ? new Error("Redis unavailable")
            : Math.min(100 * (retries + 1), 1000),
      },
    });
    // Do not log Redis error objects: connection errors can contain credentials.
    // eslint-disable-next-line no-console -- Safe operational signal, no error object.
    client.on("error", () => console.error("Redis session store unavailable"));
    try {
      await client.connect();
    } catch {
      client.destroy();
      throw new Error("Redis session store unavailable");
    }
    return new RedisSessionStore(client as RedisClientType, `${namespace}:`);
  }
  async create(input: NewSession) {
    const session = record(input);
    const ttl = Math.max(1, Math.ceil((session.expiresAt - Date.now()) / 1000));
    const saved = await this.client.set(
      this.prefix + "session:" + session.id,
      JSON.stringify(session),
      { EX: ttl, NX: true },
    );
    if (saved !== "OK") throw new Error("Session collision");
    return session;
  }
  async get(id: string | undefined, now = Date.now()) {
    if (!validId(id)) return null;
    const raw = await this.client.get(this.prefix + "session:" + id);
    if (!raw) return null;
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      await this.delete(id);
      return null;
    }
    const session = sessionSchema.safeParse(value);
    if (
      !session.success ||
      session.data.id !== id ||
      session.data.expiresAt <= now
    ) {
      await this.delete(id);
      return null;
    }
    return session.data;
  }
  async delete(id: string | undefined) {
    return validId(id)
      ? (await this.client.del(this.prefix + "session:" + id)) > 0
      : false;
  }
  async saveLogin(input: LoginTransaction) {
    const id = randomUUID();
    await this.client.set(this.prefix + "login:" + id, JSON.stringify(input), {
      EX: 600,
      NX: true,
    });
    return id;
  }
  async takeLogin(id: string) {
    if (!validId(id)) return null;
    const raw = await this.client.getDel(this.prefix + "login:" + id);
    if (!raw) return null;
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      return null;
    }
    const login = loginSchema.safeParse(value);
    return login.success && login.data.expiresAt > Date.now()
      ? login.data
      : null;
  }
  async allowLogin(key: string, limit = 10, windowSeconds = 60) {
    const count = await this.client.eval(
      "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return n",
      {
        keys: [this.prefix + "limit:" + key],
        arguments: [String(windowSeconds)],
      },
    );
    return Number(count) <= limit;
  }
  isOpen() {
    return this.client.isOpen;
  }
  async ping() {
    await this.client.ping();
  }
  async close() {
    if (this.client.isOpen) await this.client.quit();
  }
}

/** Test-only adapter. Runtime code always uses Redis. */
export class MemorySessionStore implements SessionStore {
  private readonly sessions = new Map<string, SessionRecord>();
  private readonly logins = new Map<string, LoginTransaction>();
  private readonly attempts = new Map<
    string,
    { count: number; until: number }
  >();
  async create(input: NewSession) {
    const session = record(input);
    this.sessions.set(session.id, session);
    return session;
  }
  async get(id: string | undefined, now = Date.now()) {
    const session = id ? this.sessions.get(id) : null;
    if (!session) return null;
    if (session.expiresAt <= now) {
      this.sessions.delete(session.id);
      return null;
    }
    return session;
  }
  async delete(id: string | undefined) {
    return id ? this.sessions.delete(id) : false;
  }
  async saveLogin(input: LoginTransaction) {
    const id = randomUUID();
    this.logins.set(id, input);
    return id;
  }
  async takeLogin(id: string) {
    const login = this.logins.get(id);
    this.logins.delete(id);
    return login && login.expiresAt > Date.now() ? login : null;
  }
  async allowLogin(key: string, limit = 10, windowSeconds = 60) {
    const current = this.attempts.get(key);
    const attempt =
      current && current.until > Date.now()
        ? current
        : { count: 0, until: Date.now() + windowSeconds * 1000 };
    attempt.count++;
    this.attempts.set(key, attempt);
    return attempt.count <= limit;
  }
  isOpen() {
    return true;
  }
  async ping() {}
  async close() {}
}
