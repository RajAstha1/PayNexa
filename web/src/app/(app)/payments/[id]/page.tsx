"use client";

import Link from "next/link";
import { use } from "react";
import { Card, ErrorNotice, Loading, Money, ShortId, StatusBadge, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { STATUS_INFO, kindLabel, reasonText, statusLabel } from "@/lib/client/labels";
import type { PaymentStatus } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

export default function PaymentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  // A payout that is still in flight changes on its own, so keep checking until it reaches a final status.
  const payment = useAsync((s) => api.payment(id, s), [id], {
    pollMs: 3000,
    pollWhile: (p) => p.status === "SUBMITTED" || p.status === "PENDING_REVIEW",
  });
  const history = useAsync((s) => api.paymentHistory(id, s), [id, payment.data?.status]);

  if (payment.loading) return <Loading label="Loading payment" />;
  if (payment.error) {
    return (
      <div className="stack">
        <ErrorNotice error={payment.error} />
        <Link href="/payments">Back to payments</Link>
      </div>
    );
  }
  const p = payment.data!;
  const info = STATUS_INFO[p.status as PaymentStatus];
  const live = p.status === "SUBMITTED" || p.status === "PENDING_REVIEW";

  return (
    <div className="stack">
      <div className="page-head">
        <p className="small"><Link href="/payments">← Payments</Link></p>
        <div className="row-between">
          <h1 style={{ margin: 0 }}>{kindLabel(p.kind)} · <Money amount={p.amount} currency={p.currency} /></h1>
          <StatusBadge status={p.status} />
        </div>
      </div>

      <Card>
        <p style={{ margin: 0 }}>{info.explain}</p>
        {live && <p className="muted small" style={{ margin: "0.5rem 0 0" }} aria-live="polite">This page updates automatically.</p>}
      </Card>

      <div className="grid grid-2">
        <Card title="Details">
          <dl className="kv">
            <dt>Payment ID</dt><dd><ShortId value={p.id} /></dd>
            <dt>From</dt><dd><ShortId value={p.fromAccountId} /></dd>
            {p.toAccountId && (<><dt>To</dt><dd><ShortId value={p.toAccountId} /></dd></>)}
            <dt>Created</dt><dd>{when(p.createdAt)}</dd>
            <dt>Updated</dt><dd>{when(p.updatedAt)}</dd>
            {p.reference && (<><dt>Reference</dt><dd>{p.reference}</dd></>)}
            {p.failureReason && (<><dt>Reason</dt><dd>{reasonText(p.failureReason)}</dd></>)}
          </dl>
        </Card>
        <Card title="Timeline">
          {history.loading && <Loading label="Loading history" />}
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
