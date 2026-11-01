import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { readCredentials, sessionResponse, upstreamProblem } from "@/lib/server/auth-route";
import { register } from "@/lib/server/identity";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const credentials = await readCredentials(req);
  if (credentials instanceof NextResponse) return credentials;
  try {
    return sessionResponse(req, await register(credentials.email, credentials.password, fetch, credentials.clientIp), 201, credentials.email.trim());
  } catch (error) {
    return upstreamProblem(error);
  }
}
