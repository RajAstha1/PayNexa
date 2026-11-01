"use client";
import Link from "next/link";
import { use, useMemo, useState } from "react";
import { useBalanceHidden } from "@/components/finance/balance-card";
import { TransactionList } from "@/components/finance/transactions";
import { Icon } from "@/components/icons";
import { Button, Card, CurrencyAmount, EmptyState, ErrorNotice, LinkButton, PageHeader, Pager, Segmented, ShortId, Skeleton, TransactionSkeleton } from "@/components/ui";
import { api } from "@/lib/client/api";
import { countryFor, currencyName, maskId } from "@/lib/client/format";
import { useAsync } from "@/lib/client/use-async";

const PAGE = 10;
type Dir = "all" | "in" | "out";

export default function AccountPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const account = useAsync((s) => api.account(id, s), [id]);
  const [dir, setDir] = useState<Dir>("all");
  const [page, setPage] = useState(0);
  const [hidden, setHidden] = useBalanceHidden();
  // One extra row tells us whether a next page exists without a separate count query.
  const history = useAsync(
    (s) => api.payments({ accountId: id, limit: PAGE + 1, offset: page * PAGE, direction: dir === "all" ? undefined : dir }, s),
    [id, dir, page],
    { keepPrevious: true },
  );
  const mine = useMemo(() => new Set([id]), [id]);
  const rows = history.data?.slice(0, PAGE) ?? [];

  if (account.loading) return <div className="stack"><Skeleton height={36} width={240} /><Skeleton height={240} radius={28} /></div>;
  if (account.error || !account.data) {
    return (
      <div className="stack">
        {account.error && <ErrorNotice error={account.error} />}
        <div><LinkButton href="/accounts" variant="secondary"><Icon name="arrow-left" size={16} /> Back to accounts</LinkButton></div>
      </div>
    );
  }
  const a = account.data;
  return (
    <div className="stack">
      <PageHeader
        title={<>{a.currency} account <span className="country-badge title-badge">{countryFor(a.currency)}</span></>}
        subtitle={<><Link href="/accounts">Accounts</Link> / {currencyName(a.currency)} · {maskId(a.id)}</>}
        actions={a.status === "ACTIVE" ? <LinkButton href={`/send?from=${a.id}`} variant="primary"><Icon name="send" size={18} /> Send from here</LinkButton> : <span className="badge badge-bad">Frozen</span>}
      />
      <div className="detail-grid">
        <div className="stack">
          <section className="balance-card account-hero" aria-label="Balance">
            <div className="balance-body">
              <div className="balance-top">
                <span className="balance-label">Available balance</span>
                <button type="button" className="icon-btn icon-btn-glass" aria-pressed={hidden} aria-label={hidden ? "Show balance" : "Hide balance"} onClick={() => setHidden(!hidden)}>
                  <Icon name={hidden ? "eye-off" : "eye"} size={18} />
                </button>
              </div>
              <div data-testid="account-balance"><CurrencyAmount amount={a.balance} currency={a.currency} masked={hidden} size="xl" /></div>
              <p className="balance-meta">Synthetic funds. The balance is derived from the ledger and can never go below zero.</p>
            </div>
          </section>

          <Card title="History" actions={
            <Segmented label="Direction" value={dir} onChange={(v) => { setDir(v); setPage(0); }}
              options={[{ value: "all", label: "All" }, { value: "in", label: "Incoming" }, { value: "out", label: "Outgoing" }]} />
          }>
            {history.loading ? <TransactionSkeleton rows={4} />
              : history.error ? <ErrorNotice error={history.error} onRetry={history.reload} />
              : rows.length === 0 ? <EmptyState icon="payments" title={dir === "all" ? "No activity yet" : "Nothing here"}>{dir === "all" ? "Payments to and from this account will be listed here." : "No payments match this filter."}</EmptyState>
              : <TransactionList payments={rows} mine={mine} showKind />}
            {(page > 0 || (history.data?.length ?? 0) > PAGE) && (
              <Pager page={page} hasNext={(history.data?.length ?? 0) > PAGE} busy={history.fetching} onPrev={() => setPage((p) => Math.max(0, p - 1))} onNext={() => setPage((p) => p + 1)} />
            )}
          </Card>
        </div>

        <div className="stack">
          <Card title="Details">
            <dl className="kv">
              <dt>Account ID</dt><dd><ShortId value={a.id} /></dd>
              <dt>Status</dt><dd>{a.status === "ACTIVE" ? <span className="badge badge-good">Active</span> : <span className="badge badge-bad">Frozen</span>}</dd>
              <dt>Currency</dt><dd>{a.currency} · {currencyName(a.currency)}</dd>
            </dl>
            {a.status === "FROZEN" && <p className="muted small" style={{ marginTop: "var(--s-4)" }}>This account is frozen. It cannot send or receive until an administrator lifts the freeze.</p>}
          </Card>
          <Card title="Receive money">
            <p className="muted small">Share this account ID with the sender. It identifies this {a.currency} account only; it is not a secret, but it is not guessable either.</p>
            <CopyId value={a.id} />
          </Card>
        </div>
      </div>
    </div>
  );
}

function CopyId({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="copy-id">
      <code className="copy-id-value">{value}</code>
      <Button variant="soft" className="btn-sm" onClick={() => { void navigator.clipboard?.writeText(value).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }); }}>
        <Icon name={copied ? "check" : "copy"} size={14} /> {copied ? "Copied" : "Copy ID"}
      </Button>
    </div>
  );
}
