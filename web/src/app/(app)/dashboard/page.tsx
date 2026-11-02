"use client";
import Link from "next/link";
import { useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { AccountSummaryCard } from "@/components/finance/account-card";
import { BalanceCard, useBalanceHidden } from "@/components/finance/balance-card";
import { QuickActions } from "@/components/finance/quick-actions";
import { TransactionList } from "@/components/finance/transactions";
import { VolumeChartCard } from "@/components/finance/volume-chart-card";
import { Card, EmptyState, ErrorNotice, LinkButton, Skeleton, TransactionSkeleton } from "@/components/ui";
import { api } from "@/lib/client/api";
import { firstName, greeting } from "@/lib/client/format";
import type { StatsDays } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";

export default function DashboardPage() {
  const auth = useAuth();
  const accounts = useAsync((s) => api.accounts(s), []);
  const recent = useAsync((s) => api.payments({ limit: 6 }, s), []);
  const week = useAsync((s) => api.stats(7, s), []);
  const [days, setDays] = useState<StatsDays>(30);
  const chart = useAsync((s) => api.stats(days, s), [days], { keepPrevious: true });
  const [selected, setSelected] = useState<string>("");
  const [hidden] = useBalanceHidden();
  const list = useMemo(() => accounts.data ?? [], [accounts.data]);
  const mine = useMemo(() => new Set(list.map((a) => a.id)), [list]);
  const current = list.find((a) => a.currency === selected) ?? list[0];

  return (
    <div className="stack page-dashboard">
      <header className="greeting">
        <h1>{greeting()}, {firstName(auth.email)}</h1>
        <p className="muted">Here&apos;s your payment activity at a glance.</p>
      </header>

      {accounts.error && !accounts.data && <ErrorNotice error={accounts.error} onRetry={accounts.reload} />}

      <div className="dash-hero">
        {accounts.loading && !accounts.data ? (
          <div className="balance-card balance-skeleton" role="status" aria-label="Loading balance">
            <Skeleton width={160} height={14} className="on-accent" />
            <Skeleton width={260} height={44} radius={12} className="on-accent" />
            <Skeleton width={200} height={14} className="on-accent" />
          </div>
        ) : accounts.data ? (
          <BalanceCard accounts={list} selected={current?.currency ?? ""} onSelect={setSelected} stats={week.data} statsLoading={week.loading} isAdmin={auth.isAdmin} />
        ) : null}
        <Card title="Quick actions" className="quick-card">
          <QuickActions isAdmin={auth.isAdmin} hasAccounts={list.length > 0} />
        </Card>
      </div>

      <section aria-labelledby="accounts-heading" className="stack-sm">
        <div className="section-head">
          <h2 id="accounts-heading">Your accounts</h2>
          <Link href="/accounts" className="small">Manage accounts</Link>
        </div>
        {accounts.loading && !accounts.data ? (
          <div className="acct-grid">{[0, 1, 2].map((i) => <Skeleton key={i} height={132} radius={20} />)}</div>
        ) : list.length === 0 && accounts.data ? (
          <Card><EmptyState icon="accounts" title="No accounts yet" action={<LinkButton href="/accounts">Open an account</LinkButton>}>
            An account holds one currency. Open one to send and receive synthetic funds.
          </EmptyState></Card>
        ) : (
          <div className="acct-grid">{list.map((a) => <AccountSummaryCard key={a.id} account={a} masked={hidden} />)}</div>
        )}
      </section>

      <div className="dash-lower">
        <VolumeChartCard stats={chart.data} loading={chart.loading} error={chart.error} days={days} onDays={setDays} preferred={current?.currency} onRetry={chart.reload} />
        <Card title="Recent transactions" actions={<Link href="/payments" className="small">View all</Link>} className="recent-card">
          {recent.loading && !recent.data ? (
            <TransactionSkeleton rows={5} />
          ) : recent.error && !recent.data ? (
            <ErrorNotice error={recent.error} onRetry={recent.reload} />
          ) : recent.data && recent.data.length === 0 ? (
            <EmptyState icon="payments" title="No transactions yet">Payments you send or receive will appear here.</EmptyState>
          ) : (
            <TransactionList payments={recent.data ?? []} mine={mine} />
          )}
        </Card>
      </div>
    </div>
  );
}
