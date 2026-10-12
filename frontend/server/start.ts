import { createServer } from "node:http";
import { loadConfig } from "./config.js";
import { getRuntime } from "./runtime.js";

const config = loadConfig();
await getRuntime();
const server = createServer((req, res) => {
  void getRuntime().then(
    ({ app }) => app(req, res),
    () => {
      res.writeHead(503, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      res.end(JSON.stringify({ status: 503, message: "Service unavailable" }));
    },
  );
}).listen(config.port, () =>
  // eslint-disable-next-line no-console -- Startup signal contains no configuration values.
  console.log(`Grocery BFF listening on port ${config.port}`),
);
let stopping = false;
function shutdown() {
  if (stopping) return;
  stopping = true;
  const deadline = setTimeout(() => {
    server.closeAllConnections();
    process.exit(1);
  }, 10_000);
  deadline.unref();
  server.close(() => {
    void getRuntime()
      .then(({ sessions }) => sessions.close())
      .then(
        () => {
          clearTimeout(deadline);
          process.exit(0);
        },
        () => process.exit(1),
      );
  });
}
process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
