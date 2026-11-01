import { NextResponse, type NextRequest } from "next/server";
import { clientIp } from "@/lib/server/client-ip";
import { UpstreamError } from "@/lib/server/identity";
import { decodeClaims, isExpiredOrExpiring } from "@/lib/server/jwt";
import { sharedCoordinator } from "@/lib/server/refresh-coordinator";
import { clearSessionCookies, readSession, setSessionCookies } from "@/lib/server/session-cookies";

export const dynamic = "force-dynamic";

/** Who am I? Renews the access token transparently when the refresh cookie is still good. */
export async function GET(req: NextRequest) {
  const { access, refresh } = readSession(req);
  const headers = { "Cache-Control": "no-store" };
  const anonymous = () => NextResponse.json({ authenticated: false, userId: null, roles: [] }, { headers });

  if (!isExpiredOrExpiring(access)) {
    const claims = decodeClaims(access);
    return NextResponse.json({ authenticated: true, userId: claims?.userId, roles: claims?.roles ?? [] }, { headers });
  }
  if (!refresh) return anonymous();
  try {
    const tokens = await sharedCoordinator().refresh(refresh, clientIp(req));
    const claims = decodeClaims(tokens.accessToken);
    const res = NextResponse.json({ authenticated: true, userId: claims?.userId, roles: claims?.roles ?? [] }, { headers });
    setSessionCookies(res, req, tokens);
    return res;
  } catch (error) {
    if (error instanceof UpstreamError && error.status >= 400 && error.status < 500) {
      const res = anonymous();
      clearSessionCookies(res);
      return res;
    }
    return NextResponse.json({ authenticated: false, userId: null, roles: [], unavailable: true }, { status: 503, headers });
  }
}
