import { NextResponse, type NextRequest } from "next/server";
import { logout } from "@/lib/server/identity";
import { isSameOriginRequest } from "@/lib/server/origin";
import { problem } from "@/lib/server/problem";
import { clearSessionCookies, readSession } from "@/lib/server/session-cookies";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  if (!isSameOriginRequest(req)) return problem(403, "BAD_ORIGIN", "Cross-site request rejected");
  const { refresh } = readSession(req);
  if (refresh) await logout(refresh); // revokes the whole session family server-side
  const res = new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  clearSessionCookies(res);
  return res;
}
