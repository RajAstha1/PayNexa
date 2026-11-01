import { describe, expect, it, vi } from "vitest";
import { clientIp } from "@/lib/server/client-ip";
import { login } from "@/lib/server/identity";
import { json, tokens } from "./helpers";

const req = (xff?: string) => ({ headers: new Headers(xff ? { "x-forwarded-for": xff } : {}) });

describe("clientIp", () => {
  it("is ignored unless the deployment says a trusted proxy sets it (it is trivially forged otherwise)", () => {
    expect(clientIp(req("203.0.113.9"), {} as NodeJS.ProcessEnv)).toBeUndefined();
    expect(clientIp(req("203.0.113.9"), { TRUST_PROXY_HEADERS: "false" } as unknown as NodeJS.ProcessEnv)).toBeUndefined();
  });

  it("takes the first hop when trusted, for IPv4 and IPv6", () => {
    const env = { TRUST_PROXY_HEADERS: "true" } as unknown as NodeJS.ProcessEnv;
    expect(clientIp(req("203.0.113.9, 10.0.0.1"), env)).toBe("203.0.113.9");
    expect(clientIp(req("2001:db8::1"), env)).toBe("2001:db8::1");
  });

  it("rejects values that are not addresses (no header injection into the upstream call)", () => {
    const env = { TRUST_PROXY_HEADERS: "true" } as unknown as NodeJS.ProcessEnv;
    // CR/LF values cannot even be constructed: Headers itself rejects them, so the regex is a second layer.
    for (const bad of ["not an ip", "<script>", "", "1.2.3.4; DROP", "x".repeat(60)]) {
      expect(clientIp(req(bad), env), bad).toBeUndefined();
    }
    expect(clientIp(req(), env)).toBeUndefined();
  });
});

describe("identity calls", () => {
  it("forward the end-user address so the rate limit applies per user, not per BFF", async () => {
    const fetchImpl = vi.fn(async () => json(200, { accessToken: tokens().accessToken, refreshToken: "r", expiresIn: 600 }));
    await login("a@b.co", "pw", fetchImpl as unknown as typeof fetch, "203.0.113.9");
    const init = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>)["X-Forwarded-For"]).toBe("203.0.113.9");
  });

  it("send no forwarding header when the address is unknown", async () => {
    const fetchImpl = vi.fn(async () => json(200, { accessToken: tokens().accessToken, refreshToken: "r", expiresIn: 600 }));
    await login("a@b.co", "pw", fetchImpl as unknown as typeof fetch);
    const init = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>)["X-Forwarded-For"]).toBeUndefined();
  });
});
