import type { NextRequest } from "next/server";

const IP = /^[0-9a-fA-F:.]{3,45}$/;

/**
 * The address of the end user, to pass to identity-service so ITS per-address rate limits and audit trail apply to the
 * real client and not to this server (otherwise every user behind the BFF would share one rate-limit bucket).
 *
 * X-Forwarded-For is trivially forged by a direct caller, so it is honoured ONLY when TRUST_PROXY_HEADERS=true, which
 * must be set only when a trusted reverse proxy in front of this app overwrites that header.
 */
export function clientIp(req: Pick<NextRequest, "headers">, env: NodeJS.ProcessEnv = process.env): string | undefined {
  if (env.TRUST_PROXY_HEADERS !== "true") return undefined;
  const first = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  return first && IP.test(first) ? first : undefined;
}
