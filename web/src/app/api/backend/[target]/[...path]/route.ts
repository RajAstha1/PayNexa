import { NextResponse, type NextRequest } from "next/server";
import { clientIp } from "@/lib/server/client-ip";
import { callBackend } from "@/lib/server/backend";
import { MAX_REQUEST_BODY_BYTES } from "@/lib/server/config";
import { isSameOriginRequest } from "@/lib/server/origin";
import { problem } from "@/lib/server/problem";
import { checkProxyPath, isProxyTarget } from "@/lib/server/proxy-paths";
import { sharedCoordinator } from "@/lib/server/refresh-coordinator";
import { clearSessionCookies, readSession, setSessionCookies } from "@/lib/server/session-cookies";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ target: string; path: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const { target, path } = await ctx.params;
  const method = req.method;
  if (method !== "GET" && method !== "POST") return problem(405, "METHOD_NOT_ALLOWED", "Method not allowed");
  if (!isProxyTarget(target)) return problem(404, "NOT_FOUND", "Not found");
  if (!isSameOriginRequest(req)) return problem(403, "BAD_ORIGIN", "Cross-site request rejected");

  const check = checkProxyPath(target, method, path);
  if (!check.ok) return problem(404, "NOT_FOUND", "Not found");

  let body: string | null = null;
  if (method === "POST") {
    body = await req.text();
    if (body.length > MAX_REQUEST_BODY_BYTES) return problem(413, "TOO_LARGE", "Request body too large");
    if (body.length === 0) body = null;
  }

  const { access, refresh } = readSession(req);
  const result = await callBackend(
    {
      target,
      method,
      path: check.path,
      search: req.nextUrl.search,
      body,
      idempotencyKey: req.headers.get("idempotency-key"),
      correlationId: req.headers.get("x-correlation-id"),
      access,
      refresh,
      clientIp: clientIp(req),
    },
    sharedCoordinator(),
  );

  if (result.kind === "session-ended") {
    const res = problem(401, "SESSION_ENDED", "Your session has ended. Please sign in again.");
    clearSessionCookies(res);
    return res;
  }
  if (result.kind === "unavailable") {
    return problem(503, "BACKEND_UNAVAILABLE", "The service is temporarily unavailable. Please try again.");
  }

  const res = new NextResponse(result.status === 204 ? null : result.body, { status: result.status });
  for (const [name, value] of Object.entries(result.headers)) res.headers.set(name, value);
  res.headers.set("Cache-Control", "no-store");
  if (result.newTokens) setSessionCookies(res, req, result.newTokens);
  return res;
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
