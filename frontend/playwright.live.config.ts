import { defineConfig, devices } from "@playwright/test";
import { existsSync } from "node:fs";

const baseURL = process.env.LIVE_BASE_URL;
if (!baseURL)
  throw new Error(
    "LIVE_BASE_URL is required; live tests never start a mock server",
  );
const url = new URL(baseURL);
if (
  url.protocol !== "https:" &&
  !(
    url.protocol === "http:" &&
    ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)
  )
) {
  throw new Error(
    "LIVE_BASE_URL must use HTTPS (HTTP loopback is allowed locally)",
  );
}
for (const name of ["LIVE_SESSION_A", "LIVE_SESSION_B"]) {
  if (!process.env[name] || !existsSync(process.env[name]!))
    throw new Error(
      `${name} must point to securely supplied Playwright storage state`,
    );
}
export default defineConfig({
  testDir: "./e2e-live",
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  outputDir: "test-results-live",
  reporter: "list",
  use: { baseURL, trace: "off", screenshot: "off", video: "off" },
  projects: [{ name: "live-chromium", use: { ...devices["Desktop Chrome"] } }],
});
