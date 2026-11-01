import type { Tokens } from "@/lib/server/session-cookies";

export const USER_ID = "3f2b8c1e-9a4d-4e57-8c0b-1d2e3f4a5b6c";

function b64url(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

/** An unsigned token with the claims the UI reads. The signature is irrelevant here: services verify real tokens. */
export function fakeToken(opts: { expInSeconds?: number; roles?: string[]; sub?: string } = {}): string {
  const exp = Math.floor(Date.now() / 1000) + (opts.expInSeconds ?? 600);
  return `${b64url({ alg: "RS256" })}.${b64url({ sub: opts.sub ?? USER_ID, roles: opts.roles ?? ["USER"], exp })}.sig`;
}

export function tokens(opts: { expInSeconds?: number; roles?: string[]; refresh?: string } = {}): Tokens {
  return { accessToken: fakeToken(opts), refreshToken: opts.refresh ?? "refresh-" + Math.random().toString(36).slice(2), expiresIn: 600 };
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}
