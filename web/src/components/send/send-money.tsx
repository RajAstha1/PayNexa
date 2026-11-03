"use client";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMemo, useRef, useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "@/lib/client/api";
import { currencyName, currencySymbol, maskId } from "@/lib/client/format";
import { STATUS_INFO, reasonText } from "@/lib/client/labels";
import { checkAmount, exceeds, formatMoney, fromMinorUnits, toMinorUnits } from "@/lib/client/money";
import type { Account, Payment, PaymentStatus } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";
import { Icon } from "../icons";
import { Alert, Card, CurrencyAmount, EmptyState, ErrorNotice, Field, Button, LinkButton, PageHeader, Segmented, Skeleton, StatusIndicator } from "../ui";
import { ConfirmTransferDialog, type TransferDraft } from "./confirm-dialog";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
type Mode = "transfer" | "payout";

export function SendMoney() {
  const params = useSearchParams();
  const accounts = useAsync((s) => api.accounts(s), []);
  const past = useAsync((s) => api.payments({ kind: "TRANSFER", direction: "out", limit: 40 }, s), []);
  const [mode, setMode] = useState<Mode>("transfer");
  const [fromId, setFromId] = useState(params.get("from") ?? "");
  const [target, setTarget] = useState("");
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [result, setResult] = useState<Payment | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // One idempotency key per user INTENT: reused when the same details are resubmitted (a retry after a timeout or a
  // double click cannot pay twice) and replaced as soon as any detail changes (the backend rejects a key reused with
  // different details).
  const keyRef = useRef<{ fingerprint: string; key: string } | null>(null);

  const list = useMemo(() => accounts.data ?? [], [accounts.data]);
  const from: Account | undefined = list.find((a) => a.id === fromId) ?? list.find((a) => a.status === "ACTIVE") ?? list[0];
  const currency = from?.currency ?? "USD";
  const fingerprint = JSON.stringify([mode, from?.id, target.trim().toLowerCase(), amount.trim(), reference.trim()]);

  const recipients = useMemo(() => {
    const own = new Set(list.map((a) => a.id));
    const seen = new Set<string>();
    const out: { id: string; currency: string }[] = [];
    for (const p of past.data ?? []) {
      if (!p.toAccountId || own.has(p.toAccountId) || seen.has(p.toAccountId)) continue;
      seen.add(p.toAccountId);
      out.push({ id: p.toAccountId, currency: p.currency });
    }
    return out;
  }, [past.data, list]);
  const sameCurrencyRecipients = recipients.filter((r) => r.currency === currency).slice(0, 4);
  const ownTargets = list.filter((a) => a.id !== from?.id && a.currency === currency && a.status === "ACTIVE");

  const amountCheck = checkAmount(amount, currency);
  const over = !!from && amountCheck.ok && exceeds(amount, from.balance, currency);
  const balanceAfter = from && amountCheck.ok && !over
    ? formatMoney(fromMinorUnits(toMinorUnits(from.balance, currency) - toMinorUnits(amount.trim(), currency), currency), currency)
    : null;

  function keyFor(fp: string): string {
    if (keyRef.current?.fingerprint !== fp) keyRef.current = { fingerprint: fp, key: newIdempotencyKey("web") };
    return keyRef.current.key;
  }

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!from) errs.from = "Choose an account to send from";
    else if (from.status !== "ACTIVE") errs.from = "This account is frozen and cannot send money";
    const t = target.trim();
    if (mode === "transfer") {
      if (!UUID.test(t)) errs.target = "Enter the receiving account ID (a UUID like 3f2b8c1e-…)";
      else if (from && t.toLowerCase() === from.id) errs.target = "Choose a different account than the one you send from";
    } else if (!/^[A-Za-z0-9 ._:/@+-]{1,140}$/.test(t)) {
      errs.target = "Enter a destination using letters, digits and . _ : / @ + -";
    }
    if (!amountCheck.ok) errs.amount = amountCheck.message;
    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function review(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (validate()) setConfirming(true);
  }

  async function confirm() {
    if (!from) return;
    setBusy(true);
    setError(null);
    const key = keyFor(fingerprint);
    try {
      const body = { amount: amount.trim(), currency, reference: reference.trim() || undefined };
      const payment = mode === "transfer"
        ? await api.transfer(key, { ...body, fromAccountId: from.id, toAccountId: target.trim().toLowerCase() })
        : await api.payout(key, { ...body, fromAccountId: from.id, destination: target.trim() });
      keyRef.current = null; // the intent is complete; the next payment gets a new key
      setConfirming(false);
      setResult(payment);
      accounts.reload();
      past.reload();
    } catch (err) {
      // Keep the dialog open with the error so the user can retry the very same request (same idempotency key).
      setError(err instanceof ApiError ? err : null);
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setResult(null);
    setConfirming(false);
    setTarget("");
    setAmount("");
    setReference("");
    setErrors({});
    setError(null);
  }

  if (accounts.loading && !accounts.data) {
    return <div className="stack"><Skeleton height={36} width={220} /><div className="send-grid"><Skeleton height={520} radius={22} /><Skeleton height={320} radius={22} /></div></div>;
  }
  if (accounts.error && !accounts.data) return <ErrorNotice error={accounts.error} onRetry={accounts.reload} />;
  if (list.length === 0) {
    return (
      <div className="stack">
        <PageHeader title="Send money" />
        <Card><EmptyState icon="accounts" title="You need an account first" action={<LinkButton href="/accounts">Open an account</LinkButton>}>Open an account before you send money.</EmptyState></Card>
      </div>
    );
  }
  if (result) return <ResultPanel payment={result} onAnother={reset} />;

  const draft: TransferDraft = {
    mode, fromCurrency: currency, fromId: from?.id ?? "", target: target.trim(), amount: amount.trim(), reference: reference.trim(), balanceAfter,
  };
  return (
    <div className="stack">
      <PageHeader title="Send money" subtitle="Transfers between PayNexa accounts settle immediately. Payouts leave the platform and settle once the rail confirms." />
      <div className="send-grid">
        <Card>
          <form className="form" onSubmit={review} noValidate>
            <Segmented label="Payment type" value={mode} onChange={(m) => { setMode(m); setTarget(""); setErrors({}); }}
              options={[{ value: "transfer", label: "Transfer" }, { value: "payout", label: "Payout (simulated rail)" }]} />

            <fieldset className="field source-field" aria-describedby={errors.from ? "from-error" : undefined}>
              <legend className="label">From account</legend>
              <div className="source-grid" role="radiogroup" aria-label="From account">
                {list.map((a) => (
                  <label key={a.id} className={`source-card${a.id === from?.id ? " selected" : ""}${a.status !== "ACTIVE" ? " disabled" : ""}`}>
                    <input type="radio" name="from" className="visually-hidden" value={a.id} checked={a.id === from?.id} disabled={a.status !== "ACTIVE"}
                      onChange={() => { setFromId(a.id); setTarget(""); }} />
                    <span className="acct-symbol" aria-hidden="true">{currencySymbol(a.currency)}</span>
                    <span className="source-text">
                      <strong>{a.currency} <span className="muted small num">{maskId(a.id)}</span></strong>
                      <span className="muted small">{a.status === "ACTIVE" ? `${formatMoney(a.balance, a.currency)} available` : "Frozen"}</span>
                    </span>
                    <span className="source-check" aria-hidden="true"><Icon name="check" size={14} /></span>
                  </label>
                ))}
              </div>
              {errors.from && <p className="field-msg" id="from-error"><Icon name="alert" size={14} /> {errors.from}</p>}
            </fieldset>

            {mode === "transfer" ? (
              <Field label="Receiving account ID" htmlFor="target" error={errors.target} hint="Ask the receiver for the account ID shown on their account page.">
                <input id="target" value={target} onChange={(e) => setTarget(e.target.value)} autoComplete="off" spellCheck={false}
                  placeholder="3f2b8c1e-9a4d-4e57-8c0b-1d2e3f4a5b6c" aria-invalid={!!errors.target} aria-describedby={errors.target ? "target-error" : "target-hint"} />
              </Field>
            ) : (
              <Field label="Destination" htmlFor="target" error={errors.target} hint="Sandbox rail: a destination ending in “-reject” is rejected, “-pending” settles after a delay, anything else settles.">
                <input id="target" value={target} onChange={(e) => setTarget(e.target.value)} autoComplete="off" spellCheck={false}
                  placeholder="bank:demo-ok" aria-invalid={!!errors.target} aria-describedby={errors.target ? "target-error" : "target-hint"} />
              </Field>
            )}
            {mode === "transfer" && (sameCurrencyRecipients.length > 0 || ownTargets.length > 0) && (
              <div className="recipients" aria-label="Quick recipients">
                <span className="muted small">Quick pick</span>
                <div className="chip-row">
                  {ownTargets.map((a) => (
                    <button key={a.id} type="button" className="chip chip-sm" aria-pressed={target.trim().toLowerCase() === a.id} onClick={() => setTarget(a.id)}>
                      My {a.currency} {maskId(a.id)}
                    </button>
                  ))}
                  {sameCurrencyRecipients.map((r) => (
                    <button key={r.id} type="button" className="chip chip-sm" aria-pressed={target.trim().toLowerCase() === r.id} onClick={() => setTarget(r.id)}>
                      Recent {r.currency} {maskId(r.id)}
                    </button>
                  ))}
                </div>
              </div>
            )}

            <div className="form-row">
              <Field label={`Amount (${currency})`} htmlFor="amount" error={errors.amount} hint={`${currencyName(currency)}. Both sides must use the same currency; nothing is converted.`}>
                <div className="input-affix">
                  <span className="affix" aria-hidden="true">{currencySymbol(currency)}</span>
                  <input id="amount" className="input-lg" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoComplete="off"
                    placeholder="0.00" aria-invalid={!!errors.amount} aria-describedby={errors.amount ? "amount-error" : "amount-hint"} />
                </div>
              </Field>
              <Field label="Reference (optional)" htmlFor="reference" hint={`${reference.length}/140`}>
                <input id="reference" value={reference} maxLength={140} onChange={(e) => setReference(e.target.value)} autoComplete="off" placeholder="What is this for?" />
              </Field>
            </div>
            {over && <Alert tone="warn">This is more than your available balance. The payment will fail with “Insufficient funds”.</Alert>}
            {error && !confirming && <ErrorNotice error={error} />}
            <div><Button type="submit" className="btn-lg">Review payment <Icon name="arrow-right" size={18} /></Button></div>
          </form>
        </Card>

        <aside className="summary-card" aria-label="Payment summary">
          <h2>Summary</h2>
          <div className="summary-amount">
            <span className="muted small">You send</span>
            {amountCheck.ok ? <CurrencyAmount amount={amount.trim()} currency={currency} size="lg" /> : <span className="amount amount-lg faint">{currencySymbol(currency)}0.00</span>}
          </div>
          <dl className="kv summary-kv">
            <dt>From</dt><dd>{from ? `${from.currency} ${maskId(from.id)}` : "—"}</dd>
            <dt>{mode === "transfer" ? "To" : "Destination"}</dt><dd>{target.trim() ? (mode === "transfer" && UUID.test(target.trim()) ? maskId(target.trim()) : target.trim()) : "—"}</dd>
            <dt>Fee</dt><dd>None</dd>
            <dt>Arrives</dt><dd>{mode === "transfer" ? "Immediately" : "When the rail confirms"}</dd>
            <dt>Balance after</dt><dd className="num">{balanceAfter ?? "—"}</dd>
          </dl>
          <p className="summary-note">Sandbox: synthetic money only.</p>
        </aside>
      </div>
      <ConfirmTransferDialog open={confirming} draft={draft} busy={busy} error={error} onConfirm={() => void confirm()} onCancel={() => { setConfirming(false); setError(null); }} />
    </div>
  );
}

function ResultPanel({ payment, onAnother }: { payment: Payment; onAnother: () => void }) {
  const info = STATUS_INFO[payment.status as PaymentStatus];
  const headline = payment.status === "COMPLETED" ? "Payment sent" : payment.status === "SUBMITTED" ? "Payout submitted" : payment.status === "PENDING_REVIEW" ? "Held for review" : "Payment not sent";
  const tone = info.tone === "good" || info.tone === "info" ? "good" : info.tone === "warn" ? "warn" : "bad";
  return (
    <div className="result-wrap">
      <section className={`result-card result-${tone}`} aria-labelledby="result-title">
        <span className="result-icon"><Icon name={tone === "good" ? "check" : tone === "warn" ? "clock" : "alert"} size={30} /></span>
        <h1 id="result-title">{headline}</h1>
        <CurrencyAmount amount={payment.amount} currency={payment.currency} size="lg" />
        <StatusIndicator status={payment.status} />
        <p className="muted">{info.explain}</p>
        {payment.failureReason && <p className="result-reason"><Icon name="alert" size={16} /> {reasonText(payment.failureReason)}</p>}
        <div className="result-actions">
          <LinkButton href={`/payments/${payment.id}`}>View payment</LinkButton>
          <Button variant="secondary" onClick={onAnother}>Send another</Button>
          <Link href="/dashboard" className="btn btn-ghost">Back to dashboard</Link>
        </div>
      </section>
    </div>
  );
}
