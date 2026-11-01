import type { NextRequest } from "next/server";

/**
 * CSRF defence for state-changing requests, on top of SameSite=Strict cookies.
 * A request is accepted only if the browser says it came from an allowed origin. Browsers always send `Origin` on
 * cross-origin and on same-origin POSTs; a missing `Origin` on a mutating request is rejected.
 */
export function isSafeMethod(method: string): boolean {
  return method === "GET" || method === "HEAD" || method === "OPTIONS";
}

export function allowedOrigins(req: NextRequest): Set<string> {
  const configured = (process.env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  return new Set(configured.length > 0 ? configured : [req.nextUrl.origin]);
}

export function isSameOriginRequest(req: NextRequest): boolean {
  if (isSafeMethod(req.method)) return true;
  const fetchSite = req.headers.get("sec-fetch-site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") return false;
  const origin = req.headers.get("origin");
  return !!origin && allowedOrigins(req).has(origin);
}
