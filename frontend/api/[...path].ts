import type { IncomingMessage, ServerResponse } from "node:http";
import { getRuntime } from "../server/runtime.js";

/** Vercel Node function: Redis state survives invocations, sockets are reused. */
export default async function handler(
  req: IncomingMessage,
  res: ServerResponse,
) {
  try {
    const { app } = await getRuntime();
    app(req, res);
  } catch {
    res.writeHead(503, {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    });
    res.end(JSON.stringify({ status: 503, message: "Service unavailable" }));
  }
}
