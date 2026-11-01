import { NextRequest } from "next/server";
import { afterEach, describe, expect, it } from "vitest";
import { isSameOriginRequest } from "@/lib/server/origin";

function req(method: string, headers: Record<string, string> = {}, url = "http://localhost:3000/api/auth/login") {
  return new NextRequest(url, { method, headers });
}

afterEach(() => {
  delete process.env.ALLOWED_ORIGINS;
});

describe("CSRF origin check", () => {
  it("lets safe methods through without an Origin", () => {
    expect(isSameOriginRequest(req("GET"))).toBe(true);
    expect(isSameOriginRequest(req("HEAD"))).toBe(true);
  });

  it("accepts a same-origin POST", () => {
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3000", "sec-fetch-site": "same-origin" }))).toBe(true);
  });

  it("rejects a POST from another origin", () => {
    expect(isSameOriginRequest(req("POST", { origin: "https://evil.example" }))).toBe(false);
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3001" }))).toBe(false);
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3000.evil.example" }))).toBe(false);
  });

  it("rejects a POST with no Origin at all", () => {
    expect(isSameOriginRequest(req("POST"))).toBe(false);
  });

  it("rejects when the browser says the request is cross-site, even with a matching Origin", () => {
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3000", "sec-fetch-site": "cross-site" }))).toBe(false);
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3000", "sec-fetch-site": "same-site" }))).toBe(false);
  });

  it("honours an explicit allow-list", () => {
    process.env.ALLOWED_ORIGINS = "https://console.paynexa.example, https://admin.paynexa.example";
    expect(isSameOriginRequest(req("POST", { origin: "https://console.paynexa.example" }))).toBe(true);
    expect(isSameOriginRequest(req("POST", { origin: "http://localhost:3000" }))).toBe(false);
  });
});
