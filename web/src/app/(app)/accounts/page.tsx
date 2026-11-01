"use client";
import { useMemo, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { AccountSummaryCard } from "@/components/finance/account-card";
import { useBalanceHidden } from "@/components/finance/balance-card";
import { Alert, Button, Card, EmptyState, ErrorNotice, Field, PageHeader, Skeleton } from "@/components/ui";
import { ApiError, api } from "@/lib/client/api";
import { errorText } from "@/lib/client/labels";
import { currencyName } from "@/lib/client/format";
import { CURRENCIES } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

export default function AccountsPage() {
  useAuth();
  const accounts = useAsync((s) => api.accounts(s), []);
  const [hidden] = useBalanceHidden();
  const [currency, setCurrency] = useState<string>("USD");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [opened, setOpened] = useState<string | null>(null);

  const available = useMemo(() => {
    const have = new Set(accounts.data?.map((a) => a.currency));
    return CURRENCIES.filter((c) => !have.has(c));
  }, [accounts.data]);
  const chosen = (available as readonly string[]).includes(currency) ? currency : available[0];

  async function open(e: FormEvent) {
    e.preventDefault();
    if (!chosen) return;
    setBusy(true);
    setError(null);
    setOpened(null);
    try {
      const account = await api.openAccount(chosen);
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
      <PageHeader title="Accounts" subtitle="One account per currency. Share an account ID to receive money." />

      {accounts.error && !accounts.data && <ErrorNotice error={accounts.error} onRetry={accounts.reload} />}
      {accounts.loading && !accounts.data ? (
        <div className="acct-grid">{[0, 1, 2].map((i) => <Skeleton key={i} height={140} radius={22} />)}</div>
      ) : accounts.data && accounts.data.length === 0 ? (
        <Card><EmptyState icon="accounts" title="No accounts yet">Open your first account below to get started.</EmptyState></Card>
      ) : (
        <div className="acct-grid">{accounts.data?.map((a) => <AccountSummaryCard key={a.id} account={a} masked={hidden} />)}</div>
      )}

      <Card title="Open an account" id="open">
        {available.length === 0 ? (
          <p className="muted">You already hold an account in every supported currency.</p>
        ) : (
          <form className="form open-form" onSubmit={open}>
            {error && <Alert tone="bad">{errorText(error.code, error.message)}</Alert>}
            {opened && <Alert tone="good">Your {opened} account is ready.</Alert>}
            <Field label="Currency" htmlFor="currency" hint={chosen ? `${currencyName(chosen)}. Accounts start empty.` : undefined}>
              <select id="currency" value={chosen} onChange={(e) => setCurrency(e.target.value)}>
                {available.map((c) => <option key={c} value={c}>{c} · {currencyName(c)}</option>)}
              </select>
            </Field>
            <div><Button type="submit" busy={busy}>Open account</Button></div>
          </form>
        )}
      </Card>
    </div>
  );
}
