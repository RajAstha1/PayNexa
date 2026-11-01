import { createHash } from "node:crypto";
import { UpstreamError, refresh as identityRefresh } from "./identity";
import type { Tokens } from "./session-cookies";

/**
 * Single-flight token refresh.
 *
 * identity-service rotates refresh tokens and treats a second use of an old token as theft (it revokes the whole
 * session). A page fires several API calls at once, so when the access token has just expired several requests
 * arrive carrying the SAME refresh token. If each refreshed independently, the second would look like a replay and
 * the user would be signed out.
 *
 * So concurrent requests for one refresh token share a single upstream call, and the outcome is remembered for a short
 * grace period to serve requests that were already in flight with the old cookie when the new one was issued.
 *
 * Scope: in-memory, per Next.js server instance. With several instances behind a load balancer, use sticky sessions
 * or move this to a shared store; that is documented in docs/PRODUCTION_GAP.md.
 */
export class RefreshCoordinator {
  private readonly entries = new Map<string, { promise: Promise<Tokens>; expiresAt: number }>();

  constructor(
    private readonly doRefresh: (refreshToken: string, clientIp?: string) => Promise<Tokens> = (t, ip) => identityRefresh(t, fetch, ip),
    private readonly graceMs = 30_000,
    private readonly maxEntries = 1_000,
    private readonly now: () => number = Date.now,
  ) {}

  async refresh(refreshToken: string, clientIp?: string): Promise<Tokens> {
    this.sweep();
    const key = createHash("sha256").update(refreshToken).digest("hex");
    const existing = this.entries.get(key);
    if (existing) return existing.promise;

    const promise = this.doRefresh(refreshToken, clientIp);
    this.entries.set(key, { promise, expiresAt: this.now() + this.graceMs });
    promise.catch((error: unknown) => {
      // Rejections by identity-service (invalid/expired/reused) are stable answers and stay cached for the grace
      // period so concurrent requests agree. Transient failures (timeout, 5xx) must not stick: let the next try retry.
      const definitive = error instanceof UpstreamError && error.status >= 400 && error.status < 500;
      if (!definitive) this.entries.delete(key);
    });
    return promise;
  }

  private sweep(): void {
    const t = this.now();
    for (const [key, entry] of this.entries) {
      if (entry.expiresAt <= t) this.entries.delete(key);
    }
    // Hard cap against memory growth: drop the oldest entries first.
    while (this.entries.size >= this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }

  get size(): number {
    return this.entries.size;
  }
}

/** Process-wide instance. Stored on globalThis so Next's per-route module instances share it. */
const GLOBAL_KEY = Symbol.for("paynexa.refreshCoordinator");
type GlobalWithCoordinator = typeof globalThis & { [GLOBAL_KEY]?: RefreshCoordinator };

export function sharedCoordinator(): RefreshCoordinator {
  const g = globalThis as GlobalWithCoordinator;
  g[GLOBAL_KEY] ??= new RefreshCoordinator();
  return g[GLOBAL_KEY];
}
