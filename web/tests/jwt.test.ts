import { describe, expect, it } from "vitest";
import { decodeClaims, isAdmin, isExpiredOrExpiring } from "@/lib/server/jwt";
import { USER_ID, fakeToken } from "./helpers";

describe("decodeClaims", () => {
  it("reads subject, roles and expiry", () => {
    const claims = decodeClaims(fakeToken({ roles: ["USER", "ADMIN"] }));
    expect(claims?.userId).toBe(USER_ID);
    expect(claims?.roles).toEqual(["USER", "ADMIN"]);
    expect(isAdmin(claims)).toBe(true);
  });

  it("treats a plain user as not an admin", () => {
    expect(isAdmin(decodeClaims(fakeToken({ roles: ["USER"] })))).toBe(false);
    expect(isAdmin(null)).toBe(false);
  });

  it.each([undefined, null, "", "abc", "a.b", "a.b.c.d", "a..c", "a.%%%.c"])("rejects malformed token %s", (bad) => {
    expect(decodeClaims(bad as string | undefined)).toBeNull();
  });

  it("rejects tokens whose subject is not a UUID or whose expiry is not a number", () => {
    const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
    expect(decodeClaims(`x.${enc({ sub: "admin", exp: 9999999999, roles: ["ADMIN"] })}.y`)).toBeNull();
    expect(decodeClaims(`x.${enc({ sub: USER_ID, exp: "soon" })}.y`)).toBeNull();
    expect(decodeClaims(`x.${enc("just a string")}.y`)).toBeNull();
    expect(decodeClaims(`x.${enc(null)}.y`)).toBeNull();
  });

  it("ignores non-string roles", () => {
    const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
    const claims = decodeClaims(`x.${enc({ sub: USER_ID, exp: 9999999999, roles: ["USER", 7, null, { a: 1 }] })}.y`);
    expect(claims?.roles).toEqual(["USER"]);
  });
});

describe("isExpiredOrExpiring", () => {
  it("is false for a token with plenty of life left", () => {
    expect(isExpiredOrExpiring(fakeToken({ expInSeconds: 600 }))).toBe(false);
  });

  it("is true when expired, expiring within the skew, missing or unreadable", () => {
    expect(isExpiredOrExpiring(fakeToken({ expInSeconds: -5 }))).toBe(true);
    expect(isExpiredOrExpiring(fakeToken({ expInSeconds: 10 }), Date.now(), 15)).toBe(true);
    expect(isExpiredOrExpiring(undefined)).toBe(true);
    expect(isExpiredOrExpiring("garbage")).toBe(true);
  });
});
