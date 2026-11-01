"use client";
import { useMemo, useState } from "react";
import { activeCurrencies, buildSeries, summarize, toPlotNumber } from "@/lib/client/analytics";
import { formatCompact, formatMoney, formatSigned, fromMinorUnits } from "@/lib/client/money";
import type { ApiError } from "@/lib/client/api";
import type { Stats, StatsDays } from "@/lib/client/types";
import { Chart } from "../charts/chart";
import { Card, EmptyState, ErrorNotice, Segmented, Skeleton } from "../ui";

export const PERIODS: { value: StatsDays; label: string }[] = [
  { value: 7, label: "7D" },
  { value: 30, label: "30D" },
  { value: 90, label: "90D" },
  { value: 365, label: "1Y" },
];

/**
 * Transaction volume over time for one currency at a time (currencies are never added together: there is no FX).
 * Built from /payments/stats, which counts completed, in-flight and settled payments and leaves out moves between
 * the user's own accounts.
 */
export function VolumeChartCard({ stats, loading, error, days, onDays, preferred, onRetry }: {
  stats: Stats | null; loading: boolean; error: ApiError | null; days: StatsDays; onDays: (d: StatsDays) => void; preferred?: string; onRetry: () => void;
}) {
  const currencies = useMemo(() => (stats ? activeCurrencies(stats) : []), [stats]);
  const [picked, setPicked] = useState<string | null>(null);
  const currency = picked && currencies.includes(picked) ? picked : preferred && currencies.includes(preferred) ? preferred : currencies[0];
  const points = useMemo(() => (stats && currency ? buildSeries(stats, currency) : []), [stats, currency]);
  const summary = stats && currency ? summarize(stats.totals, currency) : null;
  const hasData = !!summary && summary.count > 0;

  return (
    <Card
      title="Transaction volume"
      actions={<Segmented label="Period" options={PERIODS} value={days} onChange={onDays} />}
      className="chart-card"
    >
      {error && !stats ? (
        <ErrorNotice error={error} onRetry={onRetry} />
      ) : loading && !stats ? (
        <div className="chart-skeleton" role="status" aria-label="Loading chart"><Skeleton height={260} radius={16} /></div>
      ) : !stats || !currency || !hasData ? (
        <EmptyState icon="chart" title="Not enough activity yet">
          Volume appears here once money has moved in or out of your accounts in the last {days === 365 ? "year" : `${days} days`}. Moves between your own accounts are not counted.
        </EmptyState>
      ) : (
        <>
          <div className="chart-head">
            <div className="chart-figures">
              <div><span className="muted small">Money in</span><strong className="num amount-in">{formatSigned(summary.incoming, currency)}</strong></div>
              <div><span className="muted small">Money out</span><strong className="num">{formatSigned(-summary.outgoing, currency)}</strong></div>
              <div><span className="muted small">Payments</span><strong className="num">{summary.count}</strong></div>
            </div>
            {currencies.length > 1 && (
              <div className="chip-row" role="group" aria-label="Currency">
                {currencies.map((c) => (
                  <button key={c} type="button" className="chip chip-sm" aria-pressed={c === currency} onClick={() => setPicked(c)}>{c}</button>
                ))}
              </div>
            )}
          </div>
          <Chart
            kind="area"
            height={260}
            summary={`Transaction volume in ${currency} over the last ${days} days. Total in ${formatMoney(fromMinorUnits(summary.incoming, currency), currency)}, total out ${formatMoney(fromMinorUnits(summary.outgoing, currency), currency)}.`}
            formatTick={(n) => formatCompact(n, currency)}
            series={[{ key: "volume", label: "Volume", color: "var(--lav-500)", values: points.map((p) => toPlotNumber(p.incoming + p.outgoing, currency)) }]}
            categories={points.map((p) => ({
              label: p.label,
              title: stats.days > 30 ? `From ${p.label}` : p.label,
              rows: [
                { label: "Money in", value: formatMoney(fromMinorUnits(p.incoming, currency), currency), color: "var(--positive)" },
                { label: "Money out", value: formatMoney(fromMinorUnits(p.outgoing, currency), currency), color: "var(--lav-500)" },
                { label: "Payments", value: String(p.count) },
              ],
            }))}
          />
        </>
      )}
    </Card>
  );
}
