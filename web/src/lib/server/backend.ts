import { randomUUID } from "node:crypto";
import { UPSTREAM_TIMEOUT_MS, serviceUrl } from "./config";
import { UpstreamError } from "./identity";
import { isExpiredOrExpiring } from "./jwt";
import type { ProxyTarget } from "./proxy-paths";
import type { RefreshCoordinator } from "./refresh-coordinator";
import type { Tokens } from "./session-cookies";

export interface BackendRequest {
  target: ProxyTarget;
  method: "GET" | "POST";
  path: string;
  search: string;
  body: string | null;
  idempotencyKey: string | null;
  correlationId: string | null;
  access: string | undefined;
  refresh: string | undefined;
  /** End-user address for identity-service rate limits; see client-ip.ts. */
  clientIp?: string;
}

export type BackendResult =
  | {
      kind: "response";
      status: number;
      headers: Record<string, string>;
      body: string;
      /** Set when the access token had to be renewed to serve this request; the caller must set the cookies. */
      newTokens: Tokens | null;
    }
  | { kind: "session-ended" }
  | { kind: "unavailable" };

const FORWARDED_RESPONSE_HEADERS = ["content-type", "idempotent-replay", "retry-after", "x-correlation-id"];
/**
 * Query parameters the console is allowed to send, each with the shape of value it may carry. The query string is
 * parsed and rebuilt from this list, so nothing else (unknown names, odd characters, repeated keys) reaches a service.
 * A parameter with a malformed value is dropped; the service still validates whatever is forwarded.
 */
const QUERY_RULES: Record<string, (value: string) => boolean> = {
  limit: (v) => /^\d{1,4}$/.test(v),
  offset: (v) => /^\d{1,6}$/.test(v),
  days: (v) => /^\d{1,4}$/.test(v),
  status: (v) => /^[A-Z_]{1,24}$/.test(v),
  kind: (v) => /^[A-Z_]{1,24}$/.test(v),
  direction: (v) => v === "in" || v === "out",
  from: (v) => /^\d{4}-\d{2}-\d{2}$/.test(v),
  to: (v) => /^\d{4}-\d{2}-\d{2}$/.test(v),
  accountId: (v) => /^[0-9a-fA-F-]{36}$/.test(v),
  // Free text typed by the user: bounded, no control characters. It is URL-encoded again below.
  q: (v) => v.length >= 1 && v.length <= 50 && !/[\u0000-\u001f\u007f]/.test(v),
};
const MAX_RAW_QUERY = 400;

export function sanitizeQuery(search: string): string {
  if (!search || search.length > MAX_RAW_QUERY) return "";
  let parsed: URLSearchParams;
  try {
    parsed = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  } catch {
    return "";
  }
  const out = new URLSearchParams();
  const seen = new Set<string>();
  for (const [name, value] of parsed) {
    const rule = Object.hasOwn(QUERY_RULES, name) ? QUERY_RULES[name] : undefined;
    if (!rule || seen.has(name) || !rule(value)) continue;
    seen.add(name);
    out.append(name, value);
  }
  const text = out.toString();
  return text ? `?${text}` : "";
}
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{8,64}$/;
const CORRELATION_ID = /^[A-Za-z0-9._-]{8,64}$/;

type Fetch = typeof fetch;

export async function callBackend(
  req: BackendRequest,
  coordinator: RefreshCoordinator,
  fetchImpl: Fetch = fetch,
  nowMs: number = Date.now(),
): Promise<BackendResult> {
  let access = req.access;
  let newTokens: Tokens | null = null;

  // Proactive renewal: avoids a guaranteed 401 round trip when we already know the token is (nearly) expired.
  if (isExpiredOrExpiring(access, nowMs)) {
    if (!req.refresh) return { kind: "session-ended" };
    try {
      newTokens = await coordinator.refresh(req.refresh, req.clientIp);
      access = newTokens.accessToken;
    } catch (error) {
      return refreshFailure(error);
    }
  }

  let response = await send(req, access, fetchImpl);
  if (response === "unavailable") return { kind: "unavailable" };

  // Reactive renewal: the token looked valid locally but the service rejected it (clock skew, key rotation).
  if (response.status === 401 && !newTokens && req.refresh) {
    try {
      newTokens = await coordinator.refresh(req.refresh, req.clientIp);
    } catch (error) {
      return refreshFailure(error);
    }
    response = await send(req, newTokens.accessToken, fetchImpl);
    if (response === "unavailable") return { kind: "unavailable" };
  }

  if (response.status === 401) return { kind: "session-ended" };

  const headers: Record<string, string> = {};
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = response.headers.get(name);
    if (value) headers[name] = value;
  }
  return { kind: "response", status: response.status, headers, body: await response.text(), newTokens };
}

function refreshFailure(error: unknown): BackendResult {
  // A definitive rejection (expired, revoked, reuse detected) ends the session. Anything else is a temporary outage.
  return error instanceof UpstreamError && error.status >= 400 && error.status < 500
    ? { kind: "session-ended" }
    : { kind: "unavailable" };
}

async function send(req: BackendRequest, access: string | undefined, fetchImpl: Fetch): Promise<Response | "unavailable"> {
  const search = sanitizeQuery(req.search);
  const headers: Record<string, string> = { Accept: "application/json", "X-Correlation-Id": correlation(req.correlationId) };
  if (access) headers.Authorization = `Bearer ${access}`;
  if (req.body !== null) headers["Content-Type"] = "application/json";
  if (req.idempotencyKey && IDEMPOTENCY_KEY.test(req.idempotencyKey)) headers["Idempotency-Key"] = req.idempotencyKey;
  try {
    return await fetchImpl(`${serviceUrl(req.target)}/${req.path}${search}`, {
      method: req.method,
      headers,
      body: req.body,
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      cache: "no-store",
      redirect: "manual", // never follow a redirect to somewhere unexpected with a bearer token attached
    });
  } catch {
    return "unavailable";
  }
}

function correlation(candidate: string | null): string {
  return candidate && CORRELATION_ID.test(candidate) ? candidate : randomUUID();
}
