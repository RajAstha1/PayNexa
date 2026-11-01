import { NextResponse, type NextRequest } from "next/server";
import { clientIp } from "./client-ip";
import { MAX_REQUEST_BODY_BYTES } from "./config";
import { UpstreamError } from "./identity";
import { decodeClaims } from "./jwt";
import { isSameOriginRequest } from "./origin";
import { problem } from "./problem";
import { setSessionCookies, type Tokens } from "./session-cookies";

export async function readCredentials(req: NextRequest): Promise<{ email: string; password: string; clientIp?: string } | NextResponse> {
  if (!isSameOriginRequest(req)) return problem(403, "BAD_ORIGIN", "Cross-site request rejected");
  const text = await req.text();
  if (text.length > MAX_REQUEST_BODY_BYTES) return problem(413, "TOO_LARGE", "Request body too large");
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return problem(400, "MALFORMED_REQUEST", "The request could not be parsed");
  }
  const b = body as { email?: unknown; password?: unknown };
  if (typeof b?.email !== "string" || typeof b?.password !== "string" || b.email.length > 254 || b.password.length > 128) {
    return problem(400, "VALIDATION_FAILED", "Email and password are required");
  }
  return { email: b.email, password: b.password, clientIp: clientIp(req) };
}

/** Builds the response for a successful sign-in: the page gets identity facts, the tokens go to HttpOnly cookies. */
export function sessionResponse(req: NextRequest, tokens: Tokens, status = 200): NextResponse {
  const claims = decodeClaims(tokens.accessToken);
  const res = NextResponse.json(
    { authenticated: true, userId: claims?.userId ?? null, roles: claims?.roles ?? [] },
    { status, headers: { "Cache-Control": "no-store" } },
  );
  setSessionCookies(res, req, tokens);
  return res;
}

export function upstreamProblem(error: unknown): NextResponse {
  if (error instanceof UpstreamError) {
    // Pass through the backend's stable code and (already safe) message, but not its internals.
    return problem(error.status, error.code, error.message);
  }
  return problem(500, "INTERNAL_ERROR", "An unexpected error occurred");
}
