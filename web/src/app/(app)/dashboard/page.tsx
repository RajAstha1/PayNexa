"use client";

import Link from "next/link";
import { Alert, Card, EmptyState, ErrorNotice, Loading, Money, StatusBadge, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { kindLabel } from "@/lib/client/labels";
import { useAsync } from "@/lib/client/use-async";

export default function DashboardPage() {
  const accounts = useAsync((s) => api.accounts(s), []);
  const payments = useAsync((s) => api.payments(8, s), []);
  const review = payments.data?.filter((p) => p.status === "PENDING_REVIEW") ?? [];

  return (
    <div className="stack">
      <div className="page-head">
        <h1>Dashboard</h1>
        <p className="muted">Your accounts and latest activity.</p>
      </div>

      {review.length > 0 && (
        <Alert tone="warn">
          {review.length === 1 ? "1 payment is" : `${review.length} payments are`} under review. No money moves until an administrator approves {review.length === 1 ? "it" : "them"}.{" "}
          <Link href={`/payments/${review[0]!.id}`}>View</Link>
        </Alert>
      )}

      <Card title="Accounts" actions={<Link className="btn btn-secondary btn-sm" href="/accounts">Manage</Link>}>
        {accounts.loading && <Loading label="Loading accounts" />}
        {accounts.error && <ErrorNotice error={accounts.error} />}
        {accounts.data && accounts.data.length === 0 && (
          <EmptyState title="No accounts yet" action={<Link className="btn btn-primary" href="/accounts">Open your first account</Link>}>
            Open an account in a currency to start sending and receiving money.
          </EmptyState>
        )}
        {accounts.data && accounts.data.length > 0 && (
          <div className="grid grid-3">
            {accounts.data.map((a) => (
              <Link key={a.id} href={`/accounts/${a.id}`} className="card" style={{ textDecoration: "none", color: "inherit" }}>
                <div className="stat-label">{a.currency} account {a.status === "FROZEN" && <span className="badge badge-bad">Frozen</span>}</div>
                <div className="balance"><Money amount={a.balance} currency={a.currency} /></div>
                <div className="muted small">Available balance</div>
              </Link>
            ))}
          </div>
        )}
      </Card>

      <Card title="Recent payments" actions={<Link className="btn btn-secondary btn-sm" href="/payments">See all</Link>}>
        {payments.loading && <Loading label="Loading payments" />}
        {payments.error && <ErrorNotice error={payments.error} />}
        {payments.data && payments.data.length === 0 && (
          <EmptyState title="No payments yet" action={<Link className="btn btn-primary" href="/send">Send money</Link>}>
            Payments you send or receive will appear here.
          </EmptyState>
        )}
        {payments.data && payments.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>When</th><th>Type</th><th>Status</th><th className="right">Amount</th></tr>
              </thead>
              <tbody>
                {payments.data.map((p) => (
                  <tr key={p.id}>
                    <td><Link href={`/payments/${p.id}`}>{when(p.createdAt)}</Link></td>
                    <td>{kindLabel(p.kind)}</td>
                    <td><StatusBadge status={p.status} /></td>
                    <td className="right"><Money amount={p.amount} currency={p.currency} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
