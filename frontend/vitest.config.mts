import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "server-only": fileURLToPath(
        new URL("./src/test/server-only.ts", import.meta.url),
      ),
    },
    tsconfigPaths: true,
  },
  test: {
    environment: "jsdom",
    fileParallelism: false,
    pool: "forks",
    maxWorkers: 2,
    include: ["src/**/*.{test,spec}.{ts,tsx}", "server/**/*.{test,spec}.ts"],
    exclude: ["src/app-backup/**", "**/node_modules/**"],
    setupFiles: ["./src/test/setup.ts"],
    coverage: {
      exclude: ["src/test/**"],
      provider: "v8",
      reporter: ["text", "html", "lcov"],
    },
  },
});
