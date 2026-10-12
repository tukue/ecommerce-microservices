import { spawnSync } from "node:child_process";
if (!process.env.TEST_REDIS_URL) {
  console.error("TEST_REDIS_URL is required for real Redis integration tests");
  process.exit(1);
}
const result = spawnSync(process.execPath, ["node_modules/vitest/vitest.mjs", "run", "server/__tests__/redis.integration.test.ts"], { stdio: "inherit" });
if (result.error) { console.error("Unable to launch Redis integration tests"); process.exit(1); }
process.exit(result.status ?? 1);
