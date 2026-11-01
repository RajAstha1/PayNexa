import type { ServiceName } from "./config";

export type ProxyTarget = Exclude<ServiceName, "identity">;

const SEGMENT = /^[A-Za-z0-9._:@-]{1,100}$/;

/**
 * The BFF is not an open proxy. Only these backend routes can be reached, by method, and only through exactly these
 * shapes. Everything else is a 404 here, before any upstream call is made (no SSRF, no path tricks, no access to the
 * backend's actuator or any future endpoint by accident).
 */
const ALLOWED: Record<ProxyTarget, { method: "GET" | "POST"; pattern: RegExp }[]> = {
  payments: [
    { method: "GET", pattern: /^accounts$/ },
    { method: "POST", pattern: /^accounts$/ },
    { method: "GET", pattern: /^accounts\/[^/]+$/ },
    { method: "GET", pattern: /^payments$/ },
    { method: "GET", pattern: /^payments\/[^/]+$/ },
    { method: "GET", pattern: /^payments\/[^/]+\/history$/ },
    { method: "POST", pattern: /^payments\/transfers$/ },
    { method: "POST", pattern: /^payments\/payouts$/ },
    { method: "GET", pattern: /^admin\/payments\/pending-review$/ },
    { method: "POST", pattern: /^admin\/payments\/[^/]+\/(approve|reject|reverse)$/ },
    { method: "POST", pattern: /^admin\/accounts\/[^/]+\/(fund|status)$/ },
    { method: "POST", pattern: /^admin\/reconciliation\/run$/ },
    { method: "POST", pattern: /^admin\/outbox\/requeue-dead$/ },
  ],
  notifications: [
    { method: "GET", pattern: /^notifications$/ },
    { method: "POST", pattern: /^notifications\/[^/]+\/read$/ },
    { method: "GET", pattern: /^admin\/dead-letters$/ },
    { method: "POST", pattern: /^admin\/dead-letters\/[^/]+\/replay$/ },
  ],
};

export function isProxyTarget(value: string): value is ProxyTarget {
  return value === "payments" || value === "notifications";
}

export type PathCheck = { ok: true; path: string } | { ok: false; reason: "invalid" | "not-allowed" };

export function checkProxyPath(target: ProxyTarget, method: string, segments: string[]): PathCheck {
  if (segments.length === 0 || segments.length > 5) return { ok: false, reason: "invalid" };
  for (const s of segments) {
    if (!SEGMENT.test(s) || s === "." || s === "..") return { ok: false, reason: "invalid" };
  }
  const path = segments.join("/");
  const allowed = ALLOWED[target].some((rule) => rule.method === method && rule.pattern.test(path));
  return allowed ? { ok: true, path } : { ok: false, reason: "not-allowed" };
}
