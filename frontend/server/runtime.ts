import { createBff } from "./bff.js";
import { loadConfig } from "./config.js";
import { RedisSessionStore } from "./session-store.js";
import { RedisAuthRequestStore } from "./auth-request-store.js";

type Runtime = {
  app: ReturnType<typeof createBff>;
  sessions: RedisSessionStore;
};
let runtime: Promise<Runtime> | undefined;
let active: Runtime | undefined;
export function getRuntime() {
  // isOpen stays true during normal Redis reconnects. Only replace clients
  // whose retries were exhausted; concurrent invocations share the replacement.
  if (active && !active.sessions.isOpen()) {
    active = undefined;
    runtime = undefined;
  }
  runtime ??= (async () => {
    const config = loadConfig();
    if (!config.redisUrl) throw new Error("REDIS_URL is required");
    const sessions = await RedisSessionStore.connect(config.redisUrl);
    const authRequests =
      config.auth.mode === "oidc"
        ? await RedisAuthRequestStore.connect(config.redisUrl)
        : undefined;
    try {
      active = {
        // The third argument (verifyToken) is omitted so createBff builds its
        // default remote-JWKS verifier from the loaded config.
        app: createBff(config, sessions, undefined, { authRequests }),
        sessions,
      };
      return active;
    } catch (error) {
      await sessions.close();
      throw error;
    }
  })().catch((error: unknown) => {
    runtime = undefined;
    throw error;
  });
  return runtime;
}
