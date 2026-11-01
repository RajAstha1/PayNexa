import type { NextRequest, NextResponse } from "next/server";
import { COOKIES, REFRESH_COOKIE_MAX_AGE_SECONDS } from "./config";

export interface Tokens {
  accessToken: string;
  refreshToken: string;
  /** Access token lifetime in seconds, as reported by identity-service. */
  expiresIn: number;
}

function isHttps(req: NextRequest): boolean {
  return req.nextUrl.protocol === "https:" || req.headers.get("x-forwarded-proto") === "https";
}

/**
 * Tokens live only in HttpOnly cookies: page JavaScript (and therefore any XSS) can never read them.
 * SameSite=Strict keeps them off cross-site requests. `Secure` is set whenever the request arrived over HTTPS.
 */
export function setSessionCookies(res: NextResponse, req: NextRequest, tokens: Tokens): void {
  const secure = isHttps(req);
  res.cookies.set(COOKIES.access, tokens.accessToken, {
    httpOnly: true,
    secure,
    sameSite: "strict",
    path: "/",
    maxAge: Math.max(1, Math.floor(tokens.expiresIn)),
  });
  res.cookies.set(COOKIES.refresh, tokens.refreshToken, {
    httpOnly: true,
    secure,
    sameSite: "strict",
    path: "/api", // never sent with page navigations, only with BFF calls
    maxAge: REFRESH_COOKIE_MAX_AGE_SECONDS,
  });
}

export function clearSessionCookies(res: NextResponse): void {
  res.cookies.set(COOKIES.access, "", { httpOnly: true, sameSite: "strict", path: "/", maxAge: 0 });
  res.cookies.set(COOKIES.refresh, "", { httpOnly: true, sameSite: "strict", path: "/api", maxAge: 0 });
}

export function readSession(req: NextRequest): { access?: string; refresh?: string } {
  return { access: req.cookies.get(COOKIES.access)?.value, refresh: req.cookies.get(COOKIES.refresh)?.value };
}
