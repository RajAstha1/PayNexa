import { afterEach, describe, expect, it, vi } from "vitest";
import { callBackend, type BackendRequest } from "@/lib/server/backend";
import { gatewayHeaders } from "@/lib/server/config";
import { login } from "@/lib/server/identity";
import { RefreshCoordinator } from "@/lib/server/refresh-coordinator";
import { fakeToken, json, tokens } from "./helpers";

const KEY = "k".repeat(40);
afterEach(() => vi.unstubAllEnvs());

describe("gateway key", () => {
  it("is absent unless configured, so local development sends nothing extra", () => {
    expect(gatewayHeaders({})).toEqual({});
    expect(gatewayHeaders({ BACKEND_GATEWAY_KEY: "" })).toEqual({});
  });
  it("is sent as X-Gateway-Key when it is a plausible secret", () => {
    expect(gatewayHeaders({ BACKEND_GATEWAY_KEY: KEY })).toEqual({ "X-Gateway-Key": KEY });
  });
  it("refuses values that are too short or could break a header", () => {
    for (const bad of ["short", "has space and is long enough here", `${KEY}\r\nX-Evil: 1`, "x".repeat(200)]) {
      expect(gatewayHeaders({ BACKEND_GATEWAY_KEY: bad }), bad).toEqual({});
    }
  });
  it("is attached to calls to identity-service", async () => {
    vi.stubEnv("BACKEND_GATEWAY_KEY", KEY);
    const fetchImpl = vi.fn(async () => json(200, { accessToken: "a", refreshToken: "r", expiresIn: 600 }));
    await login("a@b.co", "pw", fetchImpl as unknown as typeof fetch);
    const init = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>)["X-Gateway-Key"]).toBe(KEY);
  });
  it("is attached to proxied calls to payments and notifications", async () => {
    vi.stubEnv("BACKEND_GATEWAY_KEY", KEY);
    const fetchImpl = vi.fn(async () => json(200, []));
    const req: BackendRequest = { target: "payments", method: "GET", path: "accounts", search: "", body: null, idempotencyKey: null, correlationId: null, access: fakeToken({ expInSeconds: 600 }), refresh: "r" };
    await callBackend(req, new RefreshCoordinator(async () => tokens()), fetchImpl as unknown as typeof fetch);
    const init = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>)["X-Gateway-Key"]).toBe(KEY);
  });
});
