"use client";

import Link from "next/link";
import { use } from "react";
import { Card, EmptyState, ErrorNotice, Loading, Money, ShortId, StatusBadge, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { kindLabel } from "@/lib/client/labels";
import { useAsync } from "@/lib/client/use-async";

export default function AccountPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const account = useAsync((s) => api.account(id, s), [id]);
  const payments = useAsync((s) => api.payments(100, s), [id]);
  const mine = payments.data?.filter((p) => p.fromAccountId === id || p.toAccountId === id) ?? [];

  if (account.loading) return <Loading label="Loading account" />;
  if (account.error) {
    return (
      <div className="stack">
        <ErrorNotice error={account.error} />
        <Link href="/accounts">Back to accounts</Link>
      </div>
    );
  }
  const a = account.data!;

  return (
    <div className="stack">
      <div className="page-head">
        <p className="small"><Link href="/accounts">← Accounts</Link></p>
        <h1>{a.currency} account</h1>
      </div>
      <div className="grid grid-2">
        <Card>
          <div className="stat-label">Balance</div>
          <div className="balance"><Money amount={a.balance} currency={a.currency} /></div>
          <p className="muted small" style={{ marginTop: "0.5rem" }}>
            The balance is derived from the immutable ledger and can never go below zero.
          </p>
        </Card>
        <Card title="Details">
          <dl className="kv">
            <dt>Account ID</dt><dd><ShortId value={a.id} /></dd>
            <dt>Status</dt><dd>{a.status === "ACTIVE" ? <span className="badge badge-good">Active</span> : <span className="badge badge-bad">Frozen</span>}</dd>
            <dt>Currency</dt><dd>{a.currency}</dd>
          </dl>
          {a.status === "FROZEN" && <p className="muted small" style={{ marginTop: "0.75rem" }}>This account is frozen. It cannot send or receive until an administrator lifts the freeze.</p>}
        </Card>
      </div>
      <Card title="Activity on this account" actions={<Link className="btn btn-primary btn-sm" href={`/send?from=${a.id}`}>Send from here</Link>}>
        {payments.loading && <Loading label="Loading activity" />}
        {payments.error && <ErrorNotice error={payments.error} />}
        {payments.data && mine.length === 0 && <EmptyState title="No activity yet" />}
        {mine.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>When</th><th>Type</th><th>Direction</th><th>Status</th><th className="right">Amount</th></tr></thead>
              <tbody>
                {mine.map((p) => {
                  const out = p.fromAccountId === id;
                  return (
                    <tr key={p.id}>
                      <td><Link href={`/payments/${p.id}`}>{when(p.createdAt)}</Link></td>
                      <td>{kindLabel(p.kind)}</td>
                      <td>{out ? "Sent" : "Received"}</td>
                      <td><StatusBadge status={p.status} /></td>
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
