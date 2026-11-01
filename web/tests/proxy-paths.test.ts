import { describe, expect, it } from "vitest";
import { checkProxyPath, isProxyTarget } from "@/lib/server/proxy-paths";

const ID = "3f2b8c1e-9a4d-4e57-8c0b-1d2e3f4a5b6c";

describe("proxy allow-list", () => {
  it("allows exactly the documented routes", () => {
    expect(checkProxyPath("payments", "GET", ["accounts"])).toEqual({ ok: true, path: "accounts" });
    expect(checkProxyPath("payments", "POST", ["payments", "transfers"]).ok).toBe(true);
    expect(checkProxyPath("payments", "GET", ["payments", ID, "history"]).ok).toBe(true);
    expect(checkProxyPath("payments", "POST", ["admin", "payments", ID, "approve"]).ok).toBe(true);
    expect(checkProxyPath("payments", "POST", ["admin", "accounts", ID, "fund"]).ok).toBe(true);
    expect(checkProxyPath("notifications", "GET", ["notifications"]).ok).toBe(true);
    expect(checkProxyPath("notifications", "POST", ["admin", "dead-letters", "12", "replay"]).ok).toBe(true);
  });

  it("enforces the HTTP method per route", () => {
    expect(checkProxyPath("payments", "POST", ["payments", ID]).ok).toBe(false);
    expect(checkProxyPath("payments", "GET", ["payments", "transfers"]).ok).toBe(true); // matches payments/{id}; the backend answers 404/400
    expect(checkProxyPath("payments", "GET", ["admin", "reconciliation", "run"]).ok).toBe(false);
    expect(checkProxyPath("payments", "GET", ["admin", "outbox", "requeue-dead"]).ok).toBe(false);
  });

  it("blocks every backend route that is not meant for the browser", () => {
    for (const path of [["actuator", "prometheus"], ["actuator", "health"], ["v3", "api-docs"], ["internal", "x"], ["admin"], ["admin", "anything"]]) {
      expect(checkProxyPath("payments", "GET", path).ok, path.join("/")).toBe(false);
      expect(checkProxyPath("payments", "POST", path).ok, path.join("/")).toBe(false);
    }
  });

  it("rejects traversal, encoded separators, empty and overlong paths", () => {
    for (const path of [[], ["..", "admin"], [".", "accounts"], ["accounts", ".."], ["a/b"], ["accounts%2F.."], ["acc ounts"],
      ["accounts", ""], ["x".repeat(101)], ["a", "b", "c", "d", "e", "f"], ["accounts;x=1"], ["accounts\\admin"]]) {
      expect(checkProxyPath("payments", "GET", path).ok, JSON.stringify(path)).toBe(false);
    }
  });

  it("does not allow reaching one service's routes through the other", () => {
    expect(checkProxyPath("notifications", "GET", ["accounts"]).ok).toBe(false);
    expect(checkProxyPath("payments", "GET", ["notifications"]).ok).toBe(false);
  });

  it("only accepts known proxy targets, so identity-service is never reachable through the proxy", () => {
    expect(isProxyTarget("payments")).toBe(true);
    expect(isProxyTarget("notifications")).toBe(true);
    for (const t of ["identity", "", "PAYMENTS", "payments/", "localhost:8081", "../identity"]) {
      expect(isProxyTarget(t), t).toBe(false);
    }
  });
});
