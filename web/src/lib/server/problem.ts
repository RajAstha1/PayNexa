import { NextResponse } from "next/server";

/** Same shape as the backend's RFC 9457 responses, so the client has exactly one error format to handle. */
export function problem(status: number, code: string, detail: string, extra?: Record<string, unknown>): NextResponse {
  return NextResponse.json(
    { status, code, detail, ...extra },
    { status, headers: { "Content-Type": "application/problem+json", "Cache-Control": "no-store" } },
  );
}
