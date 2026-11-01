import { fromMinorUnits, toMinorUnits } from "./money";
import type { Stats, StatsTotal } from "./types";

/** One plotted interval: a day, a week or a month, in UTC (the backend groups by UTC day). */
export interface Point {
  key: string;
  /** First day of the interval, YYYY-MM-DD. */
  start: string;
  label: string;
  incoming: bigint;
  outgoing: bigint;
  count: number;
}
export type Granularity = "day" | "week" | "month";

/** Long periods are grouped so the chart stays readable: 7 and 30 days by day, 90 by week, a year by month. */
export function granularityFor(days: number): Granularity {
  return days <= 30 ? "day" : days <= 90 ? "week" : "month";
}

const MS_DAY = 86_400_000;
const parseDay = (iso: string): number => Date.parse(`${iso}T00:00:00Z`);
const toDay = (ms: number): string => new Date(ms).toISOString().slice(0, 10);

function labelFor(start: string, g: Granularity): string {
  const d = new Date(parseDay(start));
  return g === "month"
    ? d.toLocaleDateString("en-US", { month: "short", timeZone: "UTC" })
    : d.toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
}

function keyFor(day: string, from: string, g: Granularity): string {
  if (g === "day") return day;
  if (g === "month") return day.slice(0, 7);
  const weeks = Math.floor((parseDay(day) - parseDay(from)) / (7 * MS_DAY));
  return toDay(parseDay(from) + weeks * 7 * MS_DAY);
}

/** Series for one currency over the whole range, with empty intervals present as zeros (so gaps look like gaps). */
export function buildSeries(stats: Stats, currency: string, g: Granularity = granularityFor(stats.days)): Point[] {
  const points = new Map<string, Point>();
  const start = parseDay(stats.from);
  const end = parseDay(stats.to);
  for (let ms = start; ms <= end; ms += MS_DAY) {
    const day = toDay(ms);
    const key = keyFor(day, stats.from, g);
    if (!points.has(key)) {
      const first = g === "month" ? `${key}-01` : key;
      points.set(key, { key, start: first < stats.from ? stats.from : first, label: labelFor(first, g), incoming: 0n, outgoing: 0n, count: 0 });
    }
  }
  for (const b of stats.buckets) {
    if (b.currency !== currency) continue;
    const p = points.get(keyFor(b.date, stats.from, g));
    if (!p) continue;
    p.incoming += toMinorUnits(b.incoming, currency);
    p.outgoing += toMinorUnits(b.outgoing, currency);
    p.count += b.count;
  }
  return [...points.values()];
}

export interface Summary {
  incoming: bigint;
  outgoing: bigint;
  net: bigint;
  count: number;
}
const ZERO: Summary = { incoming: 0n, outgoing: 0n, net: 0n, count: 0 };

export function summarize(totals: StatsTotal[], currency: string): Summary {
  const t = totals.find((x) => x.currency === currency);
  if (!t) return ZERO;
  const incoming = toMinorUnits(t.incoming, currency);
  const outgoing = toMinorUnits(t.outgoing, currency);
  return { incoming, outgoing, net: incoming - outgoing, count: t.count };
}

/** Currencies that have any activity in the period or the one before it, busiest first. */
export function activeCurrencies(stats: Stats): string[] {
  const counts = new Map<string, number>();
  for (const t of [...stats.totals, ...stats.previousTotals]) counts.set(t.currency, (counts.get(t.currency) ?? 0) + t.count);
  return [...counts.entries()].filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([c]) => c);
}

/**
 * Percentage change from `previous` to `current`, or null when there is nothing to compare against
 * (no activity before, so a percentage would be meaningless or infinite).
 */
export function percentChange(current: bigint, previous: bigint): number | null {
  if (previous === 0n) return null;
  const abs = previous < 0n ? -previous : previous;
  // basis points computed in integers, then one division for display
  return Number(((current - previous) * 10_000n) / abs) / 100;
}

/** Decimal string for plotting only (a chart needs a number; every figure the user reads comes from bigint). */
export function toPlotNumber(minor: bigint, currency: string): number {
  return Number(fromMinorUnits(minor, currency));
}
