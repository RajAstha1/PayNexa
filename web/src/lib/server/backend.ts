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
/** Filters (status, kind, direction, YYYY-MM-DD dates, paging) fit comfortably; anything with other characters is dropped, not forwarded. */
const SAFE_QUERY = /^\?[A-Za-z0-9=&._-]{0,220}$/;
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
  const search = SAFE_QUERY.test(req.search) ? req.search : "";
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
