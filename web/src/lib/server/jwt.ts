export interface Claims {
  userId: string;
  roles: string[];
  /** Seconds since epoch. */
  exp: number;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Reads the claims of an access token WITHOUT verifying its signature.
 *
 * This is only used for UI decisions (show the admin menu, know when to refresh). It is deliberately not a security
 * control: every backend call is authenticated and authorised by the service that receives it, which verifies the
 * RS256 signature, issuer, audience and expiry. A forged token here can at most change what the menu shows.
 */
export function decodeClaims(token: string | undefined | null): Claims | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    const json = Buffer.from(parts[1].replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8");
    const payload: unknown = JSON.parse(json);
    if (typeof payload !== "object" || payload === null) return null;
    const p = payload as Record<string, unknown>;
    if (typeof p.sub !== "string" || !UUID.test(p.sub)) return null;
    if (typeof p.exp !== "number" || !Number.isFinite(p.exp)) return null;
    const roles = Array.isArray(p.roles) ? p.roles.filter((r): r is string => typeof r === "string") : [];
    return { userId: p.sub, roles, exp: p.exp };
  } catch {
    return null;
  }
}

/** True when the token is missing, unreadable, or expires within `skewSeconds`. */
export function isExpiredOrExpiring(token: string | undefined | null, nowMs = Date.now(), skewSeconds = 15): boolean {
  const claims = decodeClaims(token);
  return !claims || claims.exp * 1000 - nowMs <= skewSeconds * 1000;
}

export function isAdmin(claims: Claims | null): boolean {
  return !!claims && claims.roles.includes("ADMIN");
}
