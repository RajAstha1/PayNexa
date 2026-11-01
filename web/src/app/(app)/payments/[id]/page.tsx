"use client";
import Link from "next/link";
import { use, useMemo } from "react";
import { SignedAmount } from "@/components/finance/transactions";
import { Icon } from "@/components/icons";
import { Card, ErrorNotice, LinkButton, PageHeader, ShortId, Skeleton, StatusIndicator, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { formatMoney } from "@/lib/client/money";
import { STATUS_INFO, kindLabel, reasonText, statusLabel } from "@/lib/client/labels";
import { describe } from "@/lib/client/transactions";
import type { PaymentStatus } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

export default function PaymentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  // A payout that is still in flight changes on its own, so keep checking until it reaches a final status.
  const payment = useAsync((s) => api.payment(id, s), [id], {
    pollMs: 3000,
    pollWhile: (p) => p.status === "SUBMITTED" || p.status === "PENDING_REVIEW",
  });
  const accounts = useAsync((s) => api.accounts(s), []);
  const history = useAsync((s) => api.paymentHistory(id, s), [id, payment.data?.status]);
  const mine = useMemo(() => new Set((accounts.data ?? []).map((a) => a.id)), [accounts.data]);

  if (payment.loading) return <div className="stack"><Skeleton height={32} width={220} /><Skeleton height={180} radius={22} /></div>;
  if (payment.error || !payment.data) {
    return (
      <div className="stack">
        {payment.error && <ErrorNotice error={payment.error} />}
        <div><LinkButton href="/payments" variant="secondary"><Icon name="arrow-left" size={16} /> Back to payments</LinkButton></div>
      </div>
    );
  }
  const p = payment.data;
  const info = STATUS_INFO[p.status as PaymentStatus];
  const live = p.status === "SUBMITTED" || p.status === "PENDING_REVIEW";
  const d = describe(p, mine);
  return (
    <div className="stack">
      <PageHeader title={d.title} subtitle={<><Link href="/payments">Payments</Link> / {kindLabel(p.kind)}</>} actions={<StatusIndicator status={p.status} />} />
      <section className={`txn-hero txn-hero-${info.tone}`} aria-label="Summary">
        <span className="txn-hero-label">{d.direction === "in" ? "Received" : d.direction === "out" ? "Sent" : "Moved"}</span>
        <div className="txn-hero-amount"><SignedAmount payment={p} direction={d.direction} /><span className="visually-hidden">{formatMoney(p.amount, p.currency)}</span></div>
        <p>{info.explain}</p>
        {live && <p className="muted small" aria-live="polite"><span className="spinner" aria-hidden="true" /> This page updates automatically.</p>}
        {p.failureReason && <p className="txn-hero-reason"><Icon name="alert" size={16} /> {reasonText(p.failureReason)}</p>}
      </section>
      <div className="grid grid-2">
        <Card title="Details">
          <dl className="kv">
            <dt>Payment ID</dt><dd><ShortId value={p.id} /></dd>
            <dt>Type</dt><dd>{kindLabel(p.kind)}</dd>
            <dt>From</dt><dd><ShortId value={p.fromAccountId} /> {mine.has(p.fromAccountId) && <span className="badge badge-neutral">Your account</span>}</dd>
            {p.toAccountId && (<><dt>To</dt><dd><ShortId value={p.toAccountId} /> {mine.has(p.toAccountId) && <span className="badge badge-neutral">Your account</span>}</dd></>)}
            <dt>Created</dt><dd>{when(p.createdAt)}</dd>
            <dt>Updated</dt><dd>{when(p.updatedAt)}</dd>
            {p.reference && (<><dt>Reference</dt><dd>{p.reference}</dd></>)}
          </dl>
        </Card>
        <Card title="Timeline">
          {history.loading && <div className="stack-sm"><Skeleton height={16} width="60%" /><Skeleton height={16} width="45%" /></div>}
          {history.error && <ErrorNotice error={history.error} />}
          {history.data && (
            <ol className="timeline" aria-label="Status history">
              {history.data.map((h, i) => (
                <li key={i}>
                  <strong>{statusLabel(h.to)}</strong>
                  <div className="muted small">{when(h.at)}{h.from ? ` · from ${statusLabel(h.from)}` : ""}</div>
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>
    </div>
  );
}
