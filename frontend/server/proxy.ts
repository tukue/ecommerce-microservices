import type { ServiceUrls } from "./config.js";

export type ServiceName = keyof ServiceUrls;
export interface ResolvedRoute {
  protected: boolean;
  service: ServiceName;
  target: string;
  upstreamPath: string;
}
type RouteRule = {
  methods: readonly string[];
  pattern: RegExp;
  service: ServiceName;
  protected: boolean;
  transform?: (path: string) => string;
};

const rules: readonly RouteRule[] = [
  {
    methods: ["GET"],
    pattern: /^\/api\/customer\/ledger$/,
    service: "ledger",
    protected: true,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/customer\/ledger\/orders\/\d+\/receipt$/,
    service: "ledger",
    protected: true,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/catalog\/products$/,
    service: "product",
    protected: false,
    transform: stripCatalog,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/catalog\/products\/search$/,
    service: "product",
    protected: false,
    transform: stripCatalog,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/catalog\/products\/\d+$/,
    service: "product",
    protected: false,
    transform: stripCatalog,
  },
  {
    methods: ["GET", "POST"],
    pattern: /^\/api\/customer\/cart$/,
    service: "cart",
    protected: true,
  },
  {
    methods: ["POST"],
    pattern: /^\/api\/customer\/cart\/\d+\/items$/,
    service: "cart",
    protected: true,
  },
  {
    methods: ["PATCH", "DELETE"],
    pattern: /^\/api\/customer\/cart\/\d+\/items\/\d+$/,
    service: "cart",
    protected: true,
  },
  {
    methods: ["POST"],
    pattern: /^\/api\/customer\/checkout$/,
    service: "order",
    protected: true,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/customer\/orders$/,
    service: "order",
    protected: true,
  },
  {
    methods: ["GET"],
    pattern: /^\/api\/customer\/orders\/\d+$/,
    service: "order",
    protected: true,
  },
  {
    methods: ["POST"],
    pattern: /^\/api\/customer\/orders\/\d+\/payment-session$/,
    service: "order",
    protected: true,
  },
];

function stripCatalog(path: string): string {
  return path.replace(/^\/api\/catalog/, "");
}

export function resolveService(
  method: string,
  path: string,
  serviceUrls: ServiceUrls,
  gatewayUrl?: string,
): ResolvedRoute | null {
  const pathname = path.split("?", 1)[0];
  const rule = rules.find(
    (candidate) =>
      candidate.methods.includes(method.toUpperCase()) &&
      candidate.pattern.test(pathname),
  );
  if (!rule) return null;
  return {
    protected: rule.protected,
    service: rule.service,
    target: serviceUrls[rule.service],
    upstreamPath:
      !gatewayUrl && rule.transform ? rule.transform(pathname) : pathname,
  };
}
