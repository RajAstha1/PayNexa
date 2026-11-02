"use client";
import { useMemo, useState } from "react";
import { Chart } from "@/components/charts/chart";
import { PERIODS } from "@/components/finance/volume-chart-card";
import { Icon } from "@/components/icons";
import { Card, EmptyState, ErrorNotice, PageHeader, Segmented, Skeleton } from "@/components/ui";
import { activeCurrencies, buildSeries, percentChange, summarize, toPlotNumber, type Summary } from "@/lib/client/analytics";
import { api } from "@/lib/client/api";
import { currencyName } from "@/lib/client/format";
import { formatCompact, formatMoney, formatSigned, fromMinorUnits } from "@/lib/client/money";
import type { StatsDays } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

function Change({ now, before }: { now: bigint; before: bigint }) {
  const pct = percentChange(now, before);
  if (pct === null) return <span className="muted small">No earlier data</span>;
  const up = pct >= 0;
  return (
    <span className="change small">
      <Icon name={up ? "arrow-up-right" : "arrow-down-left"} size={14} />
      {up ? "+" : "\u2212"}{Math.abs(pct).toFixed(1)}% <span className="muted">vs previous period</span>
    </span>
  );
}

export default function AnalyticsPage() {
  const [days, setDays] = useState<StatsDays>(30);
  const stats = useAsync((s) => api.stats(days, s), [days], { keepPrevious: true });
  const [picked, setPicked] = useState<string | null>(null);
  const data = stats.data;
  const currencies = useMemo(() => (data ? activeCurrencies(data) : []), [data]);
  const currency = picked && currencies.includes(picked) ? picked : currencies[0];
  const points = useMemo(() => (data && currency ? buildSeries(data, currency) : []), [data, currency]);
  const now: Summary | null = data && currency ? summarize(data.totals, currency) : null;
  const before: Summary | null = data && currency ? summarize(data.previousTotals, currency) : null;
  const money = (minor: bigint) => (currency ? formatMoney(fromMinorUnits(minor, currency), currency) : "");
  const periodWord = days === 365 ? "year" : `${days} days`;

  const distribution = useMemo(() => {
    if (!data) return [];
    const total = data.totals.reduce((n, t) => n + t.count, 0);
    return data.totals.filter((t) => t.count > 0).sort((a, b) => b.count - a.count).map((t) => ({ ...t, share: total ? (t.count / total) * 100 : 0 }));
  }, [data]);

  return (
    <div className="stack">
      <PageHeader title="Analytics" subtitle="Computed from your completed, in-flight and settled payments. Moves between your own accounts are not counted."
        actions={<Segmented label="Period" options={PERIODS} value={days} onChange={setDays} />} />

      {stats.error && !data ? <ErrorNotice error={stats.error} onRetry={stats.reload} />
        : stats.loading && !data ? (
          <div className="stack"><div className="kpi-grid">{[0, 1, 2, 3].map((i) => <Skeleton key={i} height={110} radius={22} />)}</div><Skeleton height={300} radius={22} /></div>
        ) : !data || !currency || !now || !before || (now.count === 0 && before.count === 0) ? (
          <Card><EmptyState icon="chart" title="Not enough activity yet">Charts appear once money has moved in or out of your accounts. Nothing is estimated or filled in.</EmptyState></Card>
        ) : (
          <>
            {currencies.length > 1 && (
              <div className="chip-row" role="group" aria-label="Currency">
                {currencies.map((c) => <button key={c} type="button" className="chip chip-sm" aria-pressed={c === currency} onClick={() => setPicked(c)}>{c} · {currencyName(c)}</button>)}
              </div>
            )}
            <div className="kpi-grid">
              <div className="kpi"><span className="muted small">Money in</span><strong className="num amount-in">{formatSigned(now.incoming, currency)}</strong><Change now={now.incoming} before={before.incoming} /></div>
              <div className="kpi"><span className="muted small">Money out</span><strong className="num">{formatSigned(-now.outgoing, currency)}</strong><Change now={now.outgoing} before={before.outgoing} /></div>
              <div className="kpi"><span className="muted small">Net movement</span><strong className="num">{formatSigned(now.net, currency)}</strong><Change now={now.net} before={before.net} /></div>
              <div className="kpi"><span className="muted small">Payments</span><strong className="num">{now.count}</strong><Change now={BigInt(now.count)} before={BigInt(before.count)} /></div>
            </div>

            <div className="grid grid-2 analytics-charts">
              <Card title="Money in vs out" actions={<div className="legend"><span><i style={{ background: "var(--positive)" }} />In</span><span><i style={{ background: "var(--accent-500)" }} />Out</span></div>}>
                <Chart
                  kind="bars"
                  summary={`Money in and out in ${currency} over the last ${periodWord}.`}
                  formatTick={(n) => formatCompact(n, currency)}
                  series={[
                    { key: "in", label: "In", color: "var(--positive)", values: points.map((p) => toPlotNumber(p.incoming, currency)) },
                    { key: "out", label: "Out", color: "var(--accent-500)", values: points.map((p) => toPlotNumber(p.outgoing, currency)) },
                  ]}
                  categories={points.map((p) => ({ label: p.label, title: p.label, rows: [
                    { label: "In", value: money(p.incoming), color: "var(--positive)" },
                    { label: "Out", value: money(p.outgoing), color: "var(--accent-500)" },
                  ] }))}
                />
              </Card>
              <Card title="Payment frequency">
                <Chart
                  kind="bars"
                  summary={`Number of payments per ${days <= 30 ? "day" : days <= 90 ? "week" : "month"} in ${currency}.`}
                  integer
                  formatTick={(n) => String(Math.round(n))}
                  series={[{ key: "count", label: "Payments", color: "var(--ink-700)", values: points.map((p) => p.count) }]}
                  categories={points.map((p) => ({ label: p.label, title: p.label, rows: [{ label: "Payments", value: String(p.count) }] }))}
                />
              </Card>
            </div>

            <div className="grid grid-2">
              <Card title="Currency distribution">
                <p className="muted small dist-note">Share of payments by currency. Amounts in different currencies are never added together.</p>
                <div className="dist-bar" role="img" aria-label={distribution.map((d) => `${d.currency} ${d.share.toFixed(0)} percent`).join(", ")}>
                  {distribution.map((d, i) => <span key={d.currency} style={{ width: `${d.share}%`, background: `var(--dist-${i % 5})` }} />)}
                </div>
                <ul className="dist-list">
                  {distribution.map((d, i) => (
                    <li key={d.currency}>
                      <i style={{ background: `var(--dist-${i % 5})` }} aria-hidden="true" />
                      <strong>{d.currency}</strong>
                      <span className="muted small">{d.count} payment{d.count === 1 ? "" : "s"}</span>
                      <span className="num dist-share">{d.share.toFixed(0)}%</span>
                    </li>
                  ))}
                </ul>
              </Card>
              <Card title={`This ${days === 365 ? "year" : "period"} vs previous`}>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead><tr><th scope="col">{currency}</th><th scope="col" className="right">Now</th><th scope="col" className="right">Before</th></tr></thead>
                    <tbody>
                      <tr><th scope="row">Money in</th><td className="right num">{money(now.incoming)}</td><td className="right num muted">{money(before.incoming)}</td></tr>
                      <tr><th scope="row">Money out</th><td className="right num">{money(now.outgoing)}</td><td className="right num muted">{money(before.outgoing)}</td></tr>
                      <tr><th scope="row">Net</th><td className="right num">{formatSigned(now.net, currency)}</td><td className="right num muted">{formatSigned(before.net, currency)}</td></tr>
                      <tr><th scope="row">Payments</th><td className="right num">{now.count}</td><td className="right num muted">{before.count}</td></tr>
                    </tbody>
                  </table>
                </div>
                <p className="muted small dist-note">Previous period: the same number of days immediately before this one.</p>
              </Card>
            </div>
          </>
        )}
    </div>
  );
}
