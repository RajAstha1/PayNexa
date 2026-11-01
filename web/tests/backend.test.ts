import { describe, expect, it, vi } from "vitest";
import { callBackend, sanitizeQuery, type BackendRequest } from "@/lib/server/backend";
import { UpstreamError } from "@/lib/server/identity";
import { RefreshCoordinator } from "@/lib/server/refresh-coordinator";
import { fakeToken, json, tokens } from "./helpers";

function request(over: Partial<BackendRequest> = {}): BackendRequest {
  return {
    target: "payments", method: "GET", path: "accounts", search: "", body: null, idempotencyKey: null, correlationId: null,
    access: fakeToken(), refresh: "refresh-1", ...over,
  };
}

const neverRefresh = () => new RefreshCoordinator(async () => { throw new Error("refresh must not be called"); });

describe("callBackend", () => {
  it("forwards the bearer token and returns the upstream response untouched", async () => {
    const access = fakeToken();
    const fetchImpl = vi.fn(async () => json(200, [{ id: "a" }], { "x-correlation-id": "abc-12345678" }));

    const result = await callBackend(request({ access }), neverRefresh(), fetchImpl as unknown as typeof fetch);

    expect(result).toMatchObject({ kind: "response", status: 200, newTokens: null });
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://localhost:8082/accounts");
    expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${access}`);
    expect(init.redirect).toBe("manual");
  });

  it("refreshes first when the access token is already expired, and hands the new tokens back", async () => {
    const fresh = tokens({ refresh: "refresh-2" });
    const coordinator = new RefreshCoordinator(async () => fresh);
    const fetchImpl = vi.fn(async () => json(200, []));

    const result = await callBackend(request({ access: fakeToken({ expInSeconds: -30 }) }), coordinator, fetchImpl as unknown as typeof fetch);

    expect(result).toMatchObject({ kind: "response", status: 200, newTokens: fresh });
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${fresh.accessToken}`);
  });

  it("refreshes and retries ONCE when the service rejects a token that looked valid", async () => {
    const fresh = tokens();
    const coordinator = new RefreshCoordinator(async () => fresh);
    const fetchImpl = vi.fn().mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(200, { ok: true }));

    const result = await callBackend(request(), coordinator, fetchImpl as unknown as typeof fetch);

    expect(result).toMatchObject({ kind: "response", status: 200, newTokens: fresh });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("ends the session when it is still 401 after a refresh (no retry loop)", async () => {
    const coordinator = new RefreshCoordinator(async () => tokens());
    const fetchImpl = vi.fn(async () => json(401, {}));
    const result = await callBackend(request(), coordinator, fetchImpl as unknown as typeof fetch);
    expect(result).toEqual({ kind: "session-ended" });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("ends the session when there is no refresh cookie and the access token is gone", async () => {
    const fetchImpl = vi.fn();
    const result = await callBackend(request({ access: undefined, refresh: undefined }), neverRefresh(), fetchImpl as unknown as typeof fetch);
    expect(result).toEqual({ kind: "session-ended" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("ends the session when identity definitively rejects the refresh token", async () => {
    const coordinator = new RefreshCoordinator(async () => { throw new UpstreamError(401, "INVALID_REFRESH_TOKEN", "x"); });
    const result = await callBackend(request({ access: undefined }), coordinator, vi.fn() as unknown as typeof fetch);
    expect(result).toEqual({ kind: "session-ended" });
  });

  it("reports an outage, NOT a logout, when identity is merely unreachable", async () => {
    const coordinator = new RefreshCoordinator(async () => { throw new UpstreamError(503, "IDENTITY_UNAVAILABLE", "x"); });
    const result = await callBackend(request({ access: undefined }), coordinator, vi.fn() as unknown as typeof fetch);
    expect(result).toEqual({ kind: "unavailable" });
  });

  it("reports an outage when the backend is unreachable", async () => {
    const fetchImpl = vi.fn(async () => { throw new TypeError("fetch failed"); });
    expect(await callBackend(request(), neverRefresh(), fetchImpl as unknown as typeof fetch)).toEqual({ kind: "unavailable" });
  });

  it("passes backend errors through (a 422 is an answer, not an outage)", async () => {
    const fetchImpl = vi.fn(async () => json(422, { code: "IDEMPOTENCY_KEY_REUSED" }));
    const result = await callBackend(request({ method: "POST", path: "payments/transfers", body: "{}" }), neverRefresh(), fetchImpl as unknown as typeof fetch);
    expect(result).toMatchObject({ kind: "response", status: 422 });
  });

  it("only forwards well-formed idempotency keys, correlation ids and query strings", async () => {
    const fetchImpl = vi.fn(async () => json(200, {}));
    await callBackend(
      request({ method: "POST", body: "{}", path: "payments/transfers", idempotencyKey: "bad key\r\nX-Evil: 1", correlationId: "x\r\ny", search: "?a=b&c=<script>" }),
      neverRefresh(), fetchImpl as unknown as typeof fetch);
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    const h = init.headers as Record<string, string>;
    expect(h["Idempotency-Key"]).toBeUndefined();
    expect(h["X-Correlation-Id"]).toMatch(/^[0-9a-f-]{36}$/); // replaced by a fresh id
    expect(url).not.toContain("?");

    await callBackend(request({ method: "POST", body: "{}", path: "payments/transfers", idempotencyKey: "good-key-12345" }), neverRefresh(), fetchImpl as unknown as typeof fetch);
    expect(((fetchImpl.mock.calls[1] as unknown as [string, RequestInit])[1].headers as Record<string, string>)["Idempotency-Key"]).toBe("good-key-12345");
  });

  it("forwards a realistic filtered payments query intact", async () => {
    const fetchImpl = vi.fn(async () => json(200, []));
    const q = "?status=COMPLETED&kind=TRANSFER&direction=out&from=2026-01-01&to=2026-12-31&limit=21&offset=40";
    await callBackend(request({ path: "payments", search: q }), neverRefresh(), fetchImpl as unknown as typeof fetch);
    expect((fetchImpl.mock.calls[0] as unknown as [string])[0]).toBe("http://localhost:8082/payments" + q);
  });

  it("rebuilds the query from an allow-list: unknown names, repeats and malformed values never reach the service", () => {
    expect(sanitizeQuery("")).toBe("");
    expect(sanitizeQuery("?limit=20&evil=1&limit=99&status=not valid&direction=sideways")).toBe("?limit=20");
    expect(sanitizeQuery("?__proto__=x&constructor=y&toString=z")).toBe("");
    expect(sanitizeQuery("?accountId=not-a-uuid")).toBe("");
    expect(sanitizeQuery("?q=" + "x".repeat(51))).toBe("");
    expect(sanitizeQuery("?q=abc%00def")).toBe("");
    expect(sanitizeQuery("?" + "limit=1&".repeat(80))).toBe("");
  });
  it("carries free-text search through, re-encoded", () => {
    expect(sanitizeQuery("?q=rent%20%26%20bills%3D1")).toBe("?q=rent+%26+bills%3D1");
    expect(sanitizeQuery("?q=%E2%82%AC50")).toBe("?q=%E2%82%AC50");
    expect(sanitizeQuery("?accountId=3e5e4c1a-9a4d-4e57-8c0b-1d2e3f4a5b6c&q=o'brien(1)!")).toBe("?accountId=3e5e4c1a-9a4d-4e57-8c0b-1d2e3f4a5b6c&q=o%27brien%281%29%21");
  });
  it("many concurrent requests with an expired token trigger exactly one refresh", async () => {
    const upstream = vi.fn(async () => { await new Promise((r) => setTimeout(r, 10)); return tokens(); });
    const coordinator = new RefreshCoordinator(upstream);
    const fetchImpl = vi.fn(async () => json(200, []));
    const expired = fakeToken({ expInSeconds: -5 });

    await Promise.all(Array.from({ length: 20 }, () => callBackend(request({ access: expired }), coordinator, fetchImpl as unknown as typeof fetch)));

    expect(upstream).toHaveBeenCalledTimes(1);
    expect(fetchImpl).toHaveBeenCalledTimes(20);
  });
});
