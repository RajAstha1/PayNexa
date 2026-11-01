"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Alert, Button, Card, EmptyState, ErrorNotice, Field, Loading, Money, ShortId } from "@/components/ui";
import { ApiError, api } from "@/lib/client/api";
import { CURRENCIES } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

export default function AccountsPage() {
  const accounts = useAsync((s) => api.accounts(s), []);
  const [currency, setCurrency] = useState<string>("USD");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [opened, setOpened] = useState<string | null>(null);

  const have = new Set(accounts.data?.map((a) => a.currency));
  const available = CURRENCIES.filter((c) => !have.has(c));

  async function open(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setOpened(null);
    try {
      const account = await api.openAccount(currency);
      setOpened(account.currency);
      accounts.reload();
    } catch (err) {
      setError(err instanceof ApiError ? err : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <h1>Accounts</h1>
        <p className="muted">You can hold one account per currency. Share an account ID to receive money.</p>
      </div>

      <Card title="Your accounts">
        {accounts.loading && <Loading label="Loading accounts" />}
        {accounts.error && <ErrorNotice error={accounts.error} />}
        {accounts.data?.length === 0 && <EmptyState title="No accounts yet">Open one below to get started.</EmptyState>}
        {accounts.data && accounts.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Currency</th><th>Account ID</th><th>Status</th><th className="right">Balance</th></tr></thead>
              <tbody>
                {accounts.data.map((a) => (
                  <tr key={a.id}>
                    <td><Link href={`/accounts/${a.id}`}>{a.currency}</Link></td>
                    <td><ShortId value={a.id} /></td>
                    <td>{a.status === "ACTIVE" ? <span className="badge badge-good">Active</span> : <span className="badge badge-bad">Frozen</span>}</td>
                    <td className="right"><Money amount={a.balance} currency={a.currency} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Open an account">
        {available.length === 0 ? (
          <p className="muted">You already hold an account in every supported currency.</p>
        ) : (
          <form className="form" onSubmit={open} style={{ maxWidth: 360 }}>
            {error && <Alert tone="bad">{error.message}</Alert>}
            {opened && <Alert tone="good">Your {opened} account is ready.</Alert>}
            <Field label="Currency" htmlFor="currency">
              <select id="currency" value={available.includes(currency as never) ? currency : available[0]} onChange={(e) => setCurrency(e.target.value)}>
                {available.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </Field>
            <Button type="submit" busy={busy}>Open account</Button>
          </form>
        )}
      </Card>
    </div>
  );
}
