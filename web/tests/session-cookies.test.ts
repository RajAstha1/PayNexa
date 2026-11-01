import { NextRequest, NextResponse } from "next/server";
import { describe, expect, it } from "vitest";
import { sessionResponse } from "@/lib/server/auth-route";
import { clearSessionCookies, readEmail, setEmailCookie } from "@/lib/server/session-cookies";
import { fakeToken } from "./helpers";

const req = (cookie?: string) => new NextRequest("http://localhost:3100/api/auth/session", { headers: cookie ? { cookie } : {} });
const tokens = () => ({ accessToken: fakeToken({ expInSeconds: 600 }), refreshToken: "r".repeat(32), expiresIn: 600 });

describe("email display cookie", () => {
  it("is HttpOnly, SameSite=Strict and limited to /api", () => {
    const res = NextResponse.json({});
    setEmailCookie(res, req(), "jane@example.com");
    const c = res.cookies.get("pn_email");
    expect(c?.value).toBe("jane@example.com");
    expect(c).toMatchObject({ httpOnly: true, sameSite: "strict", path: "/api" });
  });
  it("is never set for values that are not plausible emails", () => {
    for (const bad of ["", "no-at-sign", "a b@example.com", "x@y\r\nSet-Cookie: evil=1", "@example.com", `${"a".repeat(80)}@example.com`]) {
      const res = NextResponse.json({});
      setEmailCookie(res, req(), bad);
      expect(res.cookies.get("pn_email"), bad).toBeUndefined();
    }
  });
  it("is read back only when well formed, and cleared on sign-out", () => {
    expect(readEmail(req("pn_email=jane@example.com"))).toBe("jane@example.com");
    expect(readEmail(req("pn_email=garbage"))).toBeNull();
    expect(readEmail(req())).toBeNull();
    const res = NextResponse.json({});
    clearSessionCookies(res);
    expect(res.cookies.get("pn_email")).toMatchObject({ value: "", maxAge: 0 });
  });
  it("sign-in responses carry the email to the page and set the cookie, without exposing tokens", async () => {
    const res = sessionResponse(req(), tokens(), 200, "jane@example.com");
    const body = await res.json();
    expect(body).toMatchObject({ authenticated: true, email: "jane@example.com" });
    expect(JSON.stringify(body)).not.toMatch(/eyJ/);
    expect(res.cookies.get("pn_email")?.value).toBe("jane@example.com");
    expect(res.cookies.get("pn_at")?.httpOnly).toBe(true);
  });
});
