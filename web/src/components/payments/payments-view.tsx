"use client";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState, type FormEvent } from "react";
import { api } from "@/lib/client/api";
import { formatDateTime } from "@/lib/client/format";
import { STATUS_INFO, kindLabel } from "@/lib/client/labels";
import { describe } from "@/lib/client/transactions";
import type { PaymentKind, PaymentStatus } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";
import { Icon } from "../icons";
import { Button, Card, EmptyState, ErrorNotice, PageHeader, Pager, StatusIndicator, TransactionSkeleton } from "../ui";
import { TransactionList, SignedAmount } from "../finance/transactions";

const PAGE = 15;
const DIRS = [{ id: "", label: "All" }, { id: "in", label: "Incoming" }, { id: "out", label: "Outgoing" }] as const;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const STATUSES = Object.keys(STATUS_INFO) as PaymentStatus[];
const KINDS: PaymentKind[] = ["TRANSFER", "PAYOUT", "FUNDING"];

/** The filters live in the URL, so a filtered view can be bookmarked, shared with yourself, and survives a reload. */
export function PaymentsView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const get = (k: string) => params.get(k) ?? "";
  const q = get("q").slice(0, 50);
  const status = STATUSES.includes(get("status") as PaymentStatus) ? (get("status") as PaymentStatus) : undefined;
  const kind = KINDS.includes(get("kind") as PaymentKind) ? (get("kind") as PaymentKind) : undefined;
  const dir = get("dir") === "in" || get("dir") === "out" ? (get("dir") as "in" | "out") : undefined;
  const from = DAY.test(get("from")) ? get("from") : "";
  const to = DAY.test(get("to")) ? get("to") : "";
  const page = Math.max(0, Number.parseInt(get("page") || "0", 10) || 0);
  const rangeError = from && to && from > to ? "The start date is after the end date." : null;
  const [draft, setDraft] = useState<string | null>(null);

  const set = (patch: Record<string, string | undefined>, keepPage = false) => {
    const next = new URLSearchParams(params.toString());
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    if (!keepPage) next.delete("page");
    const text = next.toString();
    router.replace(text ? `${pathname}?${text}` : pathname, { scroll: false });
  };

  const accounts = useAsync((s) => api.accounts(s), []);
  const mine = useMemo(() => new Set((accounts.data ?? []).map((a) => a.id)), [accounts.data]);
  const list = useAsync(
    (s) => api.payments({ limit: PAGE + 1, offset: page * PAGE, status, kind, direction: dir, from: from || undefined, to: to || undefined, q: q || undefined }, s),
    [page, status, kind, dir, from, to, q, rangeError],
    { keepPrevious: true, enabled: !rangeError },
  );
  const rows = list.data?.slice(0, PAGE) ?? [];
  const hasNext = (list.data?.length ?? 0) > PAGE;
  const filtered = !!(q || status || kind || from || to);

  function submitSearch(e: FormEvent) {
    e.preventDefault();
    set({ q: (draft ?? q).trim().slice(0, 50) || undefined });
    setDraft(null);
  }
  const clear = () => { setDraft(null); router.replace(pathname, { scroll: false }); };

  return (
    <div className="stack">
      <PageHeader title="Payments" subtitle="Everything sent from or received into your accounts." />
      <Card flush className="payments-card">
        <div className="payments-toolbar">
          <div className="tabs tabs-flush" role="tablist" aria-label="Direction">
            {DIRS.map((d) => (
              <button key={d.id} type="button" role="tab" aria-selected={(dir ?? "") === d.id} className="tab" onClick={() => set({ dir: d.id || undefined })}>{d.label}</button>
            ))}
          </div>
          <div className="filters">
            <form className="filter-search" onSubmit={submitSearch} role="search">
              <div className="input-icon">
                <Icon name="search" size={18} />
                <input aria-label="Search payments by reference or payment id" placeholder="Search reference or payment id" value={draft ?? q} maxLength={50}
                  onChange={(e) => setDraft(e.target.value)} />
              </div>
              <Button type="submit" variant="soft" className="btn-sm" aria-label="Search payments">Search</Button>
            </form>
            <label className="filter"><span className="visually-hidden">Status</span>
              <select aria-label="Status" value={status ?? ""} onChange={(e) => set({ status: e.target.value || undefined })}>
                <option value="">Any status</option>
                {STATUSES.map((s) => <option key={s} value={s}>{STATUS_INFO[s].label}</option>)}
              </select>
            </label>
            <label className="filter"><span className="visually-hidden">Type</span>
              <select aria-label="Type" value={kind ?? ""} onChange={(e) => set({ kind: e.target.value || undefined })}>
                <option value="">Any type</option>
                {KINDS.map((k) => <option key={k} value={k}>{kindLabel(k)}</option>)}
              </select>
            </label>
            <div className="date-range" role="group" aria-label="Date range">
              <label><span className="visually-hidden">From date</span><input type="date" aria-label="From date" value={from} max={to || undefined} onChange={(e) => set({ from: e.target.value || undefined })} /></label>
              <span className="muted" aria-hidden="true">–</span>
              <label><span className="visually-hidden">To date</span><input type="date" aria-label="To date" value={to} min={from || undefined} onChange={(e) => set({ to: e.target.value || undefined })} /></label>
            </div>
            {(filtered || dir) && <Button variant="ghost" className="btn-sm" onClick={clear}>Clear</Button>}
          </div>
          {rangeError && <p className="field-msg" role="alert"><Icon name="alert" size={14} /> {rangeError}</p>}
        </div>

        <div className="payments-body" aria-busy={list.fetching}>
          {list.loading ? <TransactionSkeleton rows={6} />
            : list.error ? <ErrorNotice error={list.error} onRetry={list.reload} />
            : rows.length === 0 ? (
              <EmptyState icon={filtered ? "filter" : "payments"} title={filtered ? "No payments match these filters" : "No payments yet"}
                action={filtered ? <Button variant="soft" onClick={clear}>Clear filters</Button> : undefined}>
                {filtered ? "Try a different search, status or date range." : "When you send or receive money it will be listed here."}
              </EmptyState>
            ) : (
              <>
                <div className="only-mobile"><TransactionList payments={rows} mine={mine} showKind /></div>
                <div className="only-desktop table-wrap">
                  <table className="data-table">
                    <caption className="visually-hidden">Payments</caption>
                    <thead><tr><th scope="col">Date</th><th scope="col">Description</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col" className="right">Amount</th></tr></thead>
                    <tbody>
                      {rows.map((p) => {
                        const d = describe(p, mine);
                        return (
                          <tr key={p.id} className={list.fetching ? "row-stale" : undefined}>
                            <td className="muted num">{formatDateTime(p.createdAt)}</td>
                            <td><Link href={`/payments/${p.id}`} className="table-link"><strong>{d.title}</strong><span className="muted small">{d.subtitle}</span></Link></td>
                            <td>{kindLabel(p.kind)}</td>
                            <td><StatusIndicator status={p.status} /></td>
                            <td className="right"><SignedAmount payment={p} direction={d.direction} /></td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
        </div>
        {(page > 0 || hasNext) && (
          <div className="payments-foot">
            <Pager page={page} hasNext={hasNext} busy={list.fetching} onPrev={() => set({ page: page - 1 > 0 ? String(page - 1) : undefined }, true)} onNext={() => set({ page: String(page + 1) }, true)} />
          </div>
        )}
      </Card>
    </div>
  );
}
