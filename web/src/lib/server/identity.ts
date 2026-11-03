import { UPSTREAM_TIMEOUT_MS, gatewayHeaders, serviceUrl } from "./config";
import type { Tokens } from "./session-cookies";

export class UpstreamError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

type Fetch = typeof fetch;

interface TokenResponse {
  accessToken?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
}

function toTokens(body: TokenResponse): Tokens {
  if (typeof body.accessToken !== "string" || typeof body.refreshToken !== "string") {
    throw new UpstreamError(502, "BAD_UPSTREAM", "identity-service returned an unexpected response");
  }
  return {
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    expiresIn: typeof body.expiresIn === "number" ? body.expiresIn : 600,
  };
}

async function post(path: string, body: unknown, fetchImpl: Fetch, clientIp?: string): Promise<Response> {
  const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json", ...gatewayHeaders() };
  if (clientIp) headers["X-Forwarded-For"] = clientIp;
  try {
    return await fetchImpl(`${serviceUrl("identity")}${path}`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
      cache: "no-store",
    });
  } catch {
    throw new UpstreamError(503, "IDENTITY_UNAVAILABLE", "The sign-in service is unavailable. Try again shortly.");
  }
}

async function readProblem(res: Response): Promise<UpstreamError> {
  let code = "ERROR";
  let detail = "Request failed";
  try {
    const p = (await res.json()) as { code?: unknown; detail?: unknown };
    if (typeof p.code === "string") code = p.code;
    if (typeof p.detail === "string") detail = p.detail;
  } catch {
    // keep the generic values
  }
  return new UpstreamError(res.status, code, detail);
}

export async function login(email: string, password: string, fetchImpl: Fetch = fetch, clientIp?: string): Promise<Tokens> {
  const res = await post("/auth/login", { email, password }, fetchImpl, clientIp);
  if (!res.ok) throw await readProblem(res);
  return toTokens((await res.json()) as TokenResponse);
}

export async function register(email: string, password: string, fetchImpl: Fetch = fetch, clientIp?: string): Promise<Tokens> {
  const res = await post("/auth/register", { email, password }, fetchImpl, clientIp);
  if (!res.ok) throw await readProblem(res);
  return toTokens((await res.json()) as TokenResponse);
}

export async function refresh(refreshToken: string, fetchImpl: Fetch = fetch, clientIp?: string): Promise<Tokens> {
  const res = await post("/auth/refresh", { refreshToken }, fetchImpl, clientIp);
  if (!res.ok) throw await readProblem(res);
  return toTokens((await res.json()) as TokenResponse);
}

/** Best effort: the cookie is cleared by the caller regardless of what identity-service says. */
export async function logout(refreshToken: string, fetchImpl: Fetch = fetch): Promise<void> {
  try {
    await post("/auth/logout", { refreshToken }, fetchImpl);
  } catch {
    // identity-service unreachable: the local session is still ended; the refresh token expires on its own
  }
}
