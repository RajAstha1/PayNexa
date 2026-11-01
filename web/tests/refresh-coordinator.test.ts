import { describe, expect, it, vi } from "vitest";
import { UpstreamError } from "@/lib/server/identity";
import { RefreshCoordinator } from "@/lib/server/refresh-coordinator";
import { tokens } from "./helpers";

describe("RefreshCoordinator", () => {
  it("shares ONE upstream call between concurrent requests with the same refresh token", async () => {
    const issued = tokens({ refresh: "r2" });
    const upstream = vi.fn(async () => {
      await new Promise((r) => setTimeout(r, 20));
      return issued;
    });
    const c = new RefreshCoordinator(upstream);

    const results = await Promise.all(Array.from({ length: 25 }, () => c.refresh("r1")));

    expect(upstream).toHaveBeenCalledTimes(1);
    expect(new Set(results.map((t) => t.refreshToken))).toEqual(new Set(["r2"]));
  });

  it("serves requests that were already in flight with the old cookie within the grace period", async () => {
    let now = 1_000;
    const upstream = vi.fn(async () => tokens({ refresh: "r2" }));
    const c = new RefreshCoordinator(upstream, 30_000, 1000, () => now);

    await c.refresh("r1");
    now += 5_000; // a straggler arrives with the old cookie after the new one was issued
    await c.refresh("r1");

    expect(upstream).toHaveBeenCalledTimes(1);
  });

  it("calls upstream again for the same token after the grace period (it will be rejected by the backend, correctly)", async () => {
    let now = 1_000;
    const upstream = vi.fn(async () => tokens());
    const c = new RefreshCoordinator(upstream, 30_000, 1000, () => now);
    await c.refresh("r1");
    now += 31_000;
    await c.refresh("r1");
    expect(upstream).toHaveBeenCalledTimes(2);
  });

  it("keeps different sessions independent", async () => {
    const upstream = vi.fn(async (t: string) => tokens({ refresh: "next-" + t }));
    const c = new RefreshCoordinator(upstream);
    const [a, b] = await Promise.all([c.refresh("alice"), c.refresh("bob")]);
    expect(upstream).toHaveBeenCalledTimes(2);
    expect(a.refreshToken).toBe("next-alice");
    expect(b.refreshToken).toBe("next-bob");
  });

  it("makes all concurrent callers agree when the backend definitively rejects the token", async () => {
    const upstream = vi.fn(async () => {
      throw new UpstreamError(401, "INVALID_REFRESH_TOKEN", "invalid");
    });
    const c = new RefreshCoordinator(upstream);
    const outcomes = await Promise.allSettled(Array.from({ length: 10 }, () => c.refresh("dead")));
    expect(upstream).toHaveBeenCalledTimes(1);
    expect(outcomes.every((o) => o.status === "rejected")).toBe(true);
    await expect(c.refresh("dead")).rejects.toBeInstanceOf(UpstreamError); // still cached, still one call
    expect(upstream).toHaveBeenCalledTimes(1);
  });

  it("does NOT cache a transient failure, so the next request retries", async () => {
    const upstream = vi
      .fn<(t: string) => Promise<ReturnType<typeof tokens>>>()
      .mockRejectedValueOnce(new UpstreamError(503, "IDENTITY_UNAVAILABLE", "down"))
      .mockResolvedValueOnce(tokens({ refresh: "ok" }));
    const c = new RefreshCoordinator(upstream);

    await expect(c.refresh("r1")).rejects.toBeInstanceOf(UpstreamError);
    await new Promise((r) => setTimeout(r, 0)); // let the cleanup handler run
    await expect(c.refresh("r1")).resolves.toMatchObject({ refreshToken: "ok" });
    expect(upstream).toHaveBeenCalledTimes(2);
  });

  it("bounds its memory", async () => {
    const c = new RefreshCoordinator(async () => tokens(), 60_000, 50);
    for (let i = 0; i < 500; i++) await c.refresh("session-" + i);
    expect(c.size).toBeLessThanOrEqual(50);
  });

  it("never stores the raw refresh token as a key", async () => {
    const c = new RefreshCoordinator(async () => tokens());
    await c.refresh("super-secret-refresh-token");
    expect(JSON.stringify([...(c as unknown as { entries: Map<string, unknown> }).entries.keys()])).not.toContain("super-secret");
  });
});
