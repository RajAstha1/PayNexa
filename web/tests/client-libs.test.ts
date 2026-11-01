import { describe, expect, it } from "vitest";
import { activeCurrencies, buildSeries, granularityFor, percentChange, summarize } from "@/lib/client/analytics";
import { queryString } from "@/lib/client/api";
import { currencySymbol, displayName, firstName, greeting, initials, maskId, relativeTime } from "@/lib/client/format";
import { notificationInfo } from "@/lib/client/labels";
import { formatSigned, fromMinorUnits, toMinorUnits } from "@/lib/client/money";
import { describe as describePayment, directionOf, moneyMoved } from "@/lib/client/transactions";
import type { Payment, Stats } from "@/lib/client/types";

const A = "11111111-1111-4111-8111-1111111111aa";
const B = "22222222-2222-4222-8222-2222222222bb";
const OUTSIDE = "33333333-3333-4333-8333-3333333333cc";

function payment(over: Partial<Payment> = {}): Payment {
  return { id: "p1", kind: "TRANSFER", status: "COMPLETED", fromAccountId: A, toAccountId: OUTSIDE, amount: "10.00", currency: "USD", reference: null, failureReason: null, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z", ...over };
}

describe("money helpers", () => {
  it("round-trips minor units exactly, including zero-decimal currencies and negatives", () => {
    for (const [text, cur] of [["12.50", "USD"], ["0.05", "USD"], ["1000000.00", "EUR"], ["500", "JPY"], ["0.00", "USD"]] as const) {
      expect(fromMinorUnits(toMinorUnits(text, cur), cur)).toBe(text);
    }
    expect(fromMinorUnits(-5n, "USD")).toBe("-0.05");
    expect(fromMinorUnits(7n, "JPY")).toBe("7");
  });
  it("signs deltas explicitly", () => {
    expect(formatSigned(1250n, "USD")).toBe("+$12.50");
    expect(formatSigned(-1250n, "USD")).toBe("\u2212$12.50");
    expect(formatSigned(0n, "USD")).toBe("$0.00");
  });
});

describe("format helpers", () => {
  it("derives a display name from the email local part only", () => {
    expect(displayName("jane.doe@example.com")).toBe("Jane Doe");
    expect(displayName("admin@paynexa.example")).toBe("Admin");
    expect(displayName("a_b-c+d@x.io")).toBe("A B");
    expect(displayName(null)).toBe("there");
    expect(firstName("jane.doe@example.com")).toBe("Jane");
    expect(initials("jane.doe@example.com")).toBe("JD");
    expect(initials("")).toBe("?");
  });
  it("greets by time of day", () => {
    expect(greeting(new Date(2026, 0, 1, 9))).toBe("Good morning");
    expect(greeting(new Date(2026, 0, 1, 14))).toBe("Good afternoon");
    expect(greeting(new Date(2026, 0, 1, 19))).toBe("Good evening");
  });
  it("masks account ids to their last four characters", () => {
    expect(maskId(A)).toBe("\u2022\u2022\u2022\u2022 11aa");
    expect(maskId(A)).not.toContain(A.slice(0, 8));
  });
  it("describes elapsed time", () => {
    const now = Date.parse("2026-03-10T12:00:00Z");
    expect(relativeTime("2026-03-10T11:59:50Z", now)).toBe("just now");
    expect(relativeTime("2026-03-10T11:30:00Z", now)).toBe("30 min ago");
    expect(relativeTime("2026-03-10T09:00:00Z", now)).toBe("3 h ago");
    expect(relativeTime("2026-03-08T12:00:00Z", now)).toBe("2 d ago");
  });
  it("knows currency symbols", () => {
    expect(currencySymbol("USD")).toBe("$");
    expect(currencySymbol("EUR")).toBe("\u20ac");
  });
});

describe("query strings", () => {
  it("skips empty values and encodes text", () => {
    expect(queryString({})).toBe("");
    expect(queryString({ limit: 11, offset: 0, status: undefined, q: "rent & bills" })).toBe("?limit=11&offset=0&q=rent+%26+bills");
  });
});

describe("transactions", () => {
  const mine = new Set([A, B]);
  it("classifies direction from the user's own account ids", () => {
    expect(directionOf(payment({ fromAccountId: A, toAccountId: OUTSIDE }), mine)).toBe("out");
    expect(directionOf(payment({ fromAccountId: OUTSIDE, toAccountId: A }), mine)).toBe("in");
    expect(directionOf(payment({ fromAccountId: A, toAccountId: B }), mine)).toBe("internal");
    expect(directionOf(payment({ kind: "PAYOUT", toAccountId: null }), mine)).toBe("out");
  });
  it("never invents counterparty names: only masked ids and the user's own reference", () => {
    const d = describePayment(payment(), mine);
    expect(d.title).toBe("To \u2022\u2022\u2022\u2022 33cc");
    expect(describePayment(payment({ reference: "rent" }), mine).title).toBe("rent");
    expect(describePayment(payment({ kind: "FUNDING", fromAccountId: OUTSIDE, toAccountId: A }), mine).title).toBe("Sandbox funding");
  });
  it("only treats completed, in-flight and settled payments as money that moved", () => {
    expect(moneyMoved(payment({ status: "COMPLETED" }))).toBe(true);
    expect(moneyMoved(payment({ status: "SUBMITTED" }))).toBe(true);
    for (const s of ["FAILED", "REJECTED", "PENDING_REVIEW", "REVERSED"] as const) expect(moneyMoved(payment({ status: s }))).toBe(false);
  });
});

describe("notification labels", () => {
  it("falls back gracefully for kinds it has not seen", () => {
    expect(notificationInfo("PAYMENT_RECEIVED").category).toBe("payments");
    expect(notificationInfo("SOMETHING_NEW").title).toBe("something new");
  });
});

function stats(over: Partial<Stats> = {}): Stats {
  return {
    days: 7, from: "2026-03-04", to: "2026-03-10",
    buckets: [
      { date: "2026-03-05", currency: "USD", incoming: "100.00", outgoing: "0.00", count: 1 },
      { date: "2026-03-05", currency: "EUR", incoming: "5.00", outgoing: "0.00", count: 1 },
      { date: "2026-03-09", currency: "USD", incoming: "0.00", outgoing: "30.25", count: 2 },
    ],
    totals: [{ currency: "USD", incoming: "100.00", outgoing: "30.25", count: 3 }, { currency: "EUR", incoming: "5.00", outgoing: "0.00", count: 1 }],
    previousTotals: [{ currency: "USD", incoming: "50.00", outgoing: "0.00", count: 1 }],
    ...over,
  };
}

describe("analytics", () => {
  it("fills every day of the range, so quiet days are zeros and not missing", () => {
    const series = buildSeries(stats(), "USD");
    expect(series).toHaveLength(7);
    expect(series.map((p) => p.start)).toEqual(["2026-03-04", "2026-03-05", "2026-03-06", "2026-03-07", "2026-03-08", "2026-03-09", "2026-03-10"]);
    expect(series[1]).toMatchObject({ incoming: 10000n, count: 1 });
    expect(series[5]).toMatchObject({ outgoing: 3025n, count: 2 });
    expect(series[0]).toMatchObject({ incoming: 0n, outgoing: 0n, count: 0 });
  });
  it("never mixes currencies", () => {
    const eur = buildSeries(stats(), "EUR");
    expect(eur.reduce((n, p) => n + p.incoming, 0n)).toBe(500n);
    expect(eur.reduce((n, p) => n + p.outgoing, 0n)).toBe(0n);
  });
  it("groups long ranges by week and month without losing or double counting a cent", () => {
    const year: Stats = {
      days: 365, from: "2025-03-11", to: "2026-03-10",
      buckets: [
        { date: "2025-03-11", currency: "USD", incoming: "1.00", outgoing: "0.00", count: 1 },
        { date: "2025-12-31", currency: "USD", incoming: "2.00", outgoing: "0.00", count: 1 },
        { date: "2026-01-01", currency: "USD", incoming: "4.00", outgoing: "0.50", count: 1 },
        { date: "2026-03-10", currency: "USD", incoming: "8.00", outgoing: "0.00", count: 1 },
      ],
      totals: [], previousTotals: [],
    };
    const months = buildSeries(year, "USD");
    expect(months).toHaveLength(13);
    expect(months.reduce((n, p) => n + p.incoming, 0n)).toBe(1500n);
    expect(months.reduce((n, p) => n + p.outgoing, 0n)).toBe(50n);
    expect(months[0]!.start).toBe("2025-03-11");
    const quarter: Stats = { ...year, days: 90, from: "2025-12-11", to: "2026-03-10", buckets: year.buckets.slice(1) };
    const weeks = buildSeries(quarter, "USD");
    expect(weeks.reduce((n, p) => n + p.incoming, 0n)).toBe(1400n);
    expect(weeks.length).toBe(13);
    expect(granularityFor(7)).toBe("day");
    expect(granularityFor(90)).toBe("week");
    expect(granularityFor(365)).toBe("month");
  });
  it("summarises totals exactly and handles absent currencies", () => {
    expect(summarize(stats().totals, "USD")).toEqual({ incoming: 10000n, outgoing: 3025n, net: 6975n, count: 3 });
    expect(summarize(stats().totals, "JPY")).toEqual({ incoming: 0n, outgoing: 0n, net: 0n, count: 0 });
  });
  it("lists active currencies busiest first, including ones active only in the previous period", () => {
    expect(activeCurrencies(stats())).toEqual(["USD", "EUR"]);
    expect(activeCurrencies(stats({ totals: [], previousTotals: [{ currency: "GBP", incoming: "1.00", outgoing: "0.00", count: 1 }] }))).toEqual(["GBP"]);
  });
  it("returns no percentage when there is nothing to compare against", () => {
    expect(percentChange(500n, 0n)).toBeNull();
    expect(percentChange(150n, 100n)).toBe(50);
    expect(percentChange(50n, 100n)).toBe(-50);
    expect(percentChange(-50n, -100n)).toBe(50);
  });
});
