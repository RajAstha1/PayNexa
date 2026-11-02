"use client";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";

/**
 * A small SVG chart (area or grouped bars) with a custom tooltip. Written for PayNexa so there is no chart library
 * to audit. Accessible: the plot is focusable, arrow keys move through the data, and the active point is announced.
 */
export interface ChartSeries {
  key: string;
  label: string;
  color: string;
  values: number[];
}
export interface ChartCategory {
  /** Short label under the axis. */
  label: string;
  /** Tooltip heading. */
  title: string;
  /** Tooltip rows, already formatted from exact values by the caller. */
  rows: { label: string; value: string; color?: string }[];
}
interface Props {
  kind: "area" | "bars";
  series: ChartSeries[];
  categories: ChartCategory[];
  /** Axis tick label for a number on the value axis. */
  formatTick: (n: number) => string;
  /** Spoken summary of the chart. */
  summary: string;
  height?: number;
  /** Value axis counts whole things (e.g. payments), so ticks must be whole numbers. */
  integer?: boolean;
}

const PAD = { top: 14, right: 12, bottom: 28, left: 52 };

function niceMax(max: number): number {
  if (max <= 0) return 1;
  const exp = Math.floor(Math.log10(max));
  const base = 10 ** exp;
  const f = max / base;
  // Steps of a quarter must land on round numbers, so only 1, 2, 4, 8 and 10 are used.
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 4 ? 4 : f <= 8 ? 8 : 10;
  return nice * base;
}

/** Smooth monotone path through points (no overshoot below the baseline). */
function smoothPath(pts: [number, number][]): string {
  if (pts.length === 0) return "";
  if (pts.length === 1) return `M${pts[0]![0]},${pts[0]![1]}`;
  const n = pts.length;
  const dx: number[] = [];
  const m: number[] = [];
  for (let i = 0; i < n - 1; i++) {
    dx.push(pts[i + 1]![0] - pts[i]![0]);
    m.push((pts[i + 1]![1] - pts[i]![1]) / dx[i]!);
  }
  const t: number[] = [m[0]!];
  for (let i = 1; i < n - 1; i++) t.push(m[i - 1]! * m[i]! <= 0 ? 0 : (m[i - 1]! + m[i]!) / 2);
  t.push(m[n - 2]!);
  for (let i = 0; i < n - 1; i++) {
    if (m[i] === 0) {
      t[i] = 0;
      t[i + 1] = 0;
      continue;
    }
    const a = t[i]! / m[i]!;
    const b = t[i + 1]! / m[i]!;
    const s = a * a + b * b;
    if (s > 9) {
      const k = 3 / Math.sqrt(s);
      t[i] = k * a * m[i]!;
      t[i + 1] = k * b * m[i]!;
    }
  }
  let d = `M${pts[0]![0]},${pts[0]![1]}`;
  for (let i = 0; i < n - 1; i++) {
    const x0 = pts[i]![0];
    const y0 = pts[i]![1];
    const x1 = pts[i + 1]![0];
    const y1 = pts[i + 1]![1];
    const h = dx[i]! / 3;
    d += ` C${x0 + h},${y0 + t[i]! * h} ${x1 - h},${y1 - t[i + 1]! * h} ${x1},${y1}`;
  }
  return d;
}

export function Chart({ kind, series, categories, formatTick, summary, height = 260, integer = false }: Props) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const [active, setActive] = useState<number | null>(null);
  const gradientId = useId();

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setWidth(Math.max(240, Math.floor(entry.contentRect.width)));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const n = categories.length;
  const compact = width < 480;
  const pad = { ...PAD, left: compact ? 44 : PAD.left };
  const plotW = Math.max(1, width - pad.left - pad.right);
  const plotH = height - pad.top - pad.bottom;
  const max = useMemo(() => {
    const top = Math.max(0, ...series.flatMap((s) => s.values));
    return integer ? Math.max(4, Math.ceil(top / 4) * 4) : niceMax(top);
  }, [series, integer]);
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * max);
  const slot = plotW / Math.max(n, 1);
  const xAt = useCallback((i: number) => (kind === "bars" ? pad.left + slot * (i + 0.5) : n === 1 ? pad.left + plotW / 2 : pad.left + (plotW * i) / (n - 1)), [kind, pad.left, slot, plotW, n]);
  const yAt = (v: number) => pad.top + plotH - (v / max) * plotH;

  // Show at most ~7 evenly spaced labels, counted back from the last one so the newest label is always present
  // and never crowds its neighbour.
  const labelEvery = Math.max(1, Math.ceil(n / (compact ? 4 : 7)));
  const showLabel = (i: number) => (n - 1 - i) % labelEvery === 0;

  const pick = (e: PointerEvent<SVGSVGElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    const x = ((e.clientX - box.left) / box.width) * width;
    let best = 0;
    let dist = Infinity;
    for (let i = 0; i < n; i++) {
      const d = Math.abs(xAt(i) - x);
      if (d < dist) {
        dist = d;
        best = i;
      }
    }
    setActive(best);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "ArrowRight") { e.preventDefault(); setActive((a) => Math.min(n - 1, (a ?? -1) + 1)); }
    else if (e.key === "ArrowLeft") { e.preventDefault(); setActive((a) => Math.max(0, (a ?? n) - 1)); }
    else if (e.key === "Home") { e.preventDefault(); setActive(0); }
    else if (e.key === "End") { e.preventDefault(); setActive(n - 1); }
    else if (e.key === "Escape") setActive(null);
  };

  const primary = series[0];
  const areaPts: [number, number][] = primary ? primary.values.map((v, i) => [xAt(i), yAt(v)]) : [];
  const line = smoothPath(areaPts);
  const baseline = pad.top + plotH;
  const area = areaPts.length > 1 ? `${line} L${areaPts[areaPts.length - 1]![0]},${baseline} L${areaPts[0]![0]},${baseline} Z` : "";
  const barGroupW = Math.min(slot * 0.8, 46);
  const barW = Math.max(4, barGroupW / series.length - 2);

  const cat = active !== null ? categories[active] : undefined;
  const tipX = active !== null ? xAt(active) : 0;
  const tipRight = tipX > width * 0.6;

  return (
    <div className="chart" ref={wrap}>
      <svg
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={summary}
        tabIndex={0}
        onPointerMove={pick}
        onPointerDown={pick}
        onPointerLeave={(e) => { if (e.pointerType === "mouse") setActive(null); }}
        onFocus={() => setActive((a) => a ?? n - 1)}
        onBlur={() => setActive(null)}
        onKeyDown={onKey}
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--accent-500)" stopOpacity="0.34" />
            <stop offset="100%" stopColor="var(--accent-500)" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        {ticks.map((t) => (
          <g key={t}>
            <line className="chart-grid" x1={pad.left} x2={width - pad.right} y1={yAt(t)} y2={yAt(t)} />
            <text className="chart-tick" x={pad.left - 8} y={yAt(t) + 4} textAnchor="end">{formatTick(t)}</text>
          </g>
        ))}
        {categories.map((c, i) => showLabel(i) && (
          <text key={i} className="chart-tick" x={xAt(i)} y={height - 8} textAnchor={i === 0 && kind === "area" ? "start" : i === n - 1 && kind === "area" ? "end" : "middle"}>{c.label}</text>
        ))}
        <g className="chart-plot">
          {kind === "area" && primary && (
            <>
              {area && <path d={area} fill={`url(#${gradientId})`} />}
              <path d={line} fill="none" stroke={primary.color} strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round" />
            </>
          )}
          {kind === "bars" && series.map((s, si) => s.values.map((v, i) => {
            const h = Math.max(v > 0 ? 2 : 0, (v / max) * plotH);
            const x = xAt(i) - barGroupW / 2 + si * (barW + 2);
            return <rect key={`${s.key}-${i}`} className="chart-bar" x={x} y={baseline - h} width={barW} height={h} rx={Math.min(5, barW / 2)} fill={s.color} opacity={active === null || active === i ? 1 : 0.45} />;
          }))}
        </g>
        {active !== null && kind === "area" && primary && (
          <g className="chart-focus">
            <line className="chart-guide" x1={tipX} x2={tipX} y1={pad.top} y2={baseline} />
            <circle cx={tipX} cy={yAt(primary.values[active] ?? 0)} r={5} fill="var(--surface)" stroke={primary.color} strokeWidth={2.5} />
          </g>
        )}
        {active !== null && kind === "bars" && <rect className="chart-hit" x={tipX - slot / 2} y={pad.top} width={slot} height={plotH} rx={8} />}
      </svg>
      {cat && (
        <div className="chart-tip" style={{ left: tipX, transform: `translateX(${tipRight ? "calc(-100% - 14px)" : "14px"})` }} aria-hidden="true">
          <div className="chart-tip-title">{cat.title}</div>
          {cat.rows.map((r) => (
            <div className="chart-tip-row" key={r.label}>
              <span className="chart-tip-label">{r.color && <i style={{ background: r.color }} />}{r.label}</span>
              <span className="num">{r.value}</span>
            </div>
          ))}
        </div>
      )}
      <div className="visually-hidden" aria-live="polite">{cat ? `${cat.title}. ${cat.rows.map((r) => `${r.label} ${r.value}`).join(". ")}` : ""}</div>
    </div>
  );
}
