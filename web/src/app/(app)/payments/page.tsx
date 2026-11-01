"use client";

import Link from "next/link";
import { useState } from "react";
import { Card, EmptyState, ErrorNotice, Loading, Money, StatusBadge, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { kindLabel, reasonText } from "@/lib/client/labels";
import { STATUS_INFO } from "@/lib/client/labels";
import { useAsync } from "@/lib/client/use-async";
import type { PaymentStatus } from "@/lib/client/types";

export default function PaymentsPage() {
  const payments = useAsync((s) => api.payments(100, s), []);
  const accounts = useAsync((s) => api.accounts(s), []);
  const [filter, setFilter] = useState<string>("ALL");

  const mine = new Set(accounts.data?.map((a) => a.id));
  const rows = (payments.data ?? []).filter((p) => filter === "ALL" || p.status === filter);

  return (
    <div className="stack">
      <div className="row-between page-head">
        <div>
          <h1>Payments</h1>
          <p className="muted" style={{ margin: 0 }}>Everything you have sent or received.</p>
        </div>
        <div className="field" style={{ margin: 0, minWidth: 190 }}>
          <label htmlFor="filter">Status</label>
          <select id="filter" value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="ALL">All statuses</option>
            {(Object.keys(STATUS_INFO) as PaymentStatus[]).map((s) => <option key={s} value={s}>{STATUS_INFO[s].label}</option>)}
          </select>
        </div>
      </div>

      <Card>
        {payments.loading && <Loading label="Loading payments" />}
        {payments.error && <ErrorNotice error={payments.error} />}
        {payments.data && rows.length === 0 && (
          <EmptyState title={filter === "ALL" ? "No payments yet" : "No payments with this status"}
            action={filter === "ALL" ? <Link className="btn btn-primary" href="/send">Send money</Link> : undefined}>
            {filter === "ALL" ? "Payments you send or receive will appear here." : "Try a different filter."}
          </EmptyState>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>When</th><th>Type</th><th>Direction</th><th>Status</th><th>Note</th><th className="right">Amount</th></tr></thead>
              <tbody>
                {rows.map((p) => {
                  const sent = mine.has(p.fromAccountId);
                  return (
                    <tr key={p.id}>
                      <td><Link href={`/payments/${p.id}`}>{when(p.createdAt)}</Link></td>
                      <td>{kindLabel(p.kind)}</td>
                      <td>{p.kind === "FUNDING" ? "Received" : sent ? "Sent" : "Received"}</td>
                      <td><StatusBadge status={p.status} /></td>
                      <td className="muted">{reasonText(p.failureReason) ?? p.reference ?? ""}</td>
                      <td className="right"><Money amount={p.amount} currency={p.currency} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
