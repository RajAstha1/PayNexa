"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMemo, useRef, useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "@/lib/client/api";
import { STATUS_INFO, reasonText } from "@/lib/client/labels";
import { checkAmount, exceeds, formatMoney } from "@/lib/client/money";
import type { Payment, PaymentStatus } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";
import { Alert, Button, Card, EmptyState, ErrorNotice, Field, Loading, Money, StatusBadge } from "./ui";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Mode = "transfer" | "payout";

export function SendMoney() {
  const params = useSearchParams();
  const accounts = useAsync((s) => api.accounts(s), []);
  const [mode, setMode] = useState<Mode>("transfer");
  const [fromId, setFromId] = useState(params.get("from") ?? "");
  const [target, setTarget] = useState(""); // payee account id (transfer) or destination (payout)
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [step, setStep] = useState<"edit" | "review">("edit");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [result, setResult] = useState<Payment | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // One idempotency key per user INTENT. It is reused when the same details are resubmitted (a retry after a timeout
  // or a double click cannot pay twice) and replaced as soon as any detail changes (the backend rejects a key that is
  // reused with different details).
  const keyRef = useRef<{ fingerprint: string; key: string } | null>(null);

  const list = accounts.data ?? [];
  const from = list.find((a) => a.id === (fromId || list[0]?.id));
  const currency = from?.currency ?? "USD";

  const fingerprint = useMemo(() => JSON.stringify([mode, from?.id, target.trim(), amount.trim(), reference.trim()]), [mode, from?.id, target, amount, reference]);

  function keyFor(fp: string): string {
    if (keyRef.current?.fingerprint !== fp) keyRef.current = { fingerprint: fp, key: newIdempotencyKey("web") };
    return keyRef.current.key;
  }

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!from) errs.from = "Choose an account to send from";
    if (mode === "transfer") {
      if (!UUID.test(target.trim())) errs.target = "Enter the receiving account ID (a UUID like 3f2b8c1e-…)";
      else if (from && target.trim().toLowerCase() === from.id) errs.target = "Choose a different account than the one you send from";
    } else if (!/^[A-Za-z0-9 ._:/@+-]{1,140}$/.test(target.trim())) {
      errs.target = "Enter a destination using letters, digits and . _ : / @ + -";
    }
    const check = checkAmount(amount, currency);
    if (!check.ok) errs.amount = check.message;
    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function review(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (validate()) setStep("review");
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
      setResult(payment);
      keyRef.current = null; // the intent is complete; the next payment gets a new key
      accounts.reload();
    } catch (err) {
      setError(err instanceof ApiError ? err : null);
      setStep("edit");
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setResult(null);
    setStep("edit");
    setTarget("");
    setAmount("");
    setReference("");
    setErrors({});
    setError(null);
  }

  if (accounts.loading) return <Loading label="Loading your accounts" />;
  if (accounts.error) return <ErrorNotice error={accounts.error} />;
  if (list.length === 0) {
    return (
      <EmptyState title="You need an account first" action={<Link className="btn btn-primary" href="/accounts">Open an account</Link>}>
        Open an account before you send money.
      </EmptyState>
    );
  }

  if (result) return <ResultCard payment={result} onAnother={reset} />;

  const tooMuch = from && checkAmount(amount, currency).ok && exceeds(amount, from.balance, currency);

  return (
    <div className="stack" style={{ maxWidth: 640 }}>
      <div className="page-head">
        <h1>Send money</h1>
        <p className="muted">Transfers between PayNexa accounts settle immediately. Payouts leave the platform and are only settled once the rail confirms.</p>
      </div>

      <Card>
        <div className="tabs" role="tablist" aria-label="Payment type">
          {(["transfer", "payout"] as const).map((m) => (
            <button key={m} type="button" role="tab" id={`tab-${m}`} aria-selected={mode === m} aria-controls="send-panel" className="tab"
              onClick={() => { setMode(m); setTarget(""); setErrors({}); setStep("edit"); }}>
              {m === "transfer" ? "Transfer" : "Payout (simulated rail)"}
            </button>
          ))}
        </div>

        <div id="send-panel" role="tabpanel" aria-labelledby={`tab-${mode}`}>
          {error && <div style={{ marginBottom: "1rem" }}><ErrorNotice error={error} /></div>}

          {step === "edit" ? (
            <form className="form" onSubmit={review} noValidate>
              <Field label="From account" htmlFor="from" error={errors.from}>
                <select id="from" value={from?.id ?? ""} onChange={(e) => setFromId(e.target.value)}>
                  {list.map((a) => (
                    <option key={a.id} value={a.id} disabled={a.status !== "ACTIVE"}>
                      {a.currency} · {formatMoney(a.balance, a.currency)} available{a.status !== "ACTIVE" ? " (frozen)" : ""}
                    </option>
                  ))}
                </select>
              </Field>

              {mode === "transfer" ? (
                <Field label="Receiving account ID" htmlFor="target" error={errors.target} hint="Ask the receiver for the account ID shown on their Accounts page.">
                  <input id="target" value={target} onChange={(e) => setTarget(e.target.value)} autoComplete="off" spellCheck={false}
                    placeholder="3f2b8c1e-9a4d-4e57-8c0b-1d2e3f4a5b6c" aria-invalid={!!errors.target} />
                </Field>
              ) : (
                <Field label="Destination" htmlFor="target" error={errors.target} hint="Sandbox rail: a destination ending in “-reject” is rejected, “-pending” settles after a delay, anything else settles.">
                  <input id="target" value={target} onChange={(e) => setTarget(e.target.value)} autoComplete="off" spellCheck={false}
                    placeholder="bank:demo-ok" aria-invalid={!!errors.target} />
                </Field>
              )}

              <div className="form-row">
                <Field label={`Amount (${currency})`} htmlFor="amount" error={errors.amount}>
                  <input id="amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoComplete="off"
                    placeholder="0.00" aria-invalid={!!errors.amount} />
                </Field>
                <Field label="Reference (optional)" htmlFor="reference">
                  <input id="reference" value={reference} maxLength={140} onChange={(e) => setReference(e.target.value)} autoComplete="off" />
                </Field>
              </div>

              {tooMuch && <div style={{ marginBottom: "1rem" }}><Alert tone="warn">This is more than your available balance. The payment will fail with “Insufficient funds”.</Alert></div>}
              <Button type="submit">Review payment</Button>
            </form>
          ) : (
            <div className="stack">
              <h2>Review and confirm</h2>
              <dl className="kv">
                <dt>Type</dt><dd>{mode === "transfer" ? "Transfer" : "Payout (simulated rail)"}</dd>
                <dt>From</dt><dd>{from?.currency} account <code>{from?.id.slice(0, 8)}…</code></dd>
                <dt>{mode === "transfer" ? "To account" : "Destination"}</dt><dd><code>{target.trim()}</code></dd>
                <dt>Amount</dt><dd><strong><Money amount={amount.trim()} currency={currency} /></strong></dd>
                {reference.trim() && (<><dt>Reference</dt><dd>{reference.trim()}</dd></>)}
              </dl>
              {mode === "payout" && <Alert tone="info">A payout is in flight until the rail confirms it. The money leaves your balance now but is not settled yet.</Alert>}
              <div className="row">
                <Button onClick={() => void confirm()} busy={busy}>Confirm and send</Button>
                <Button variant="secondary" onClick={() => setStep("edit")} disabled={busy}>Back</Button>
              </div>
              <p className="muted small">If your connection drops, you can press confirm again: the same request is never applied twice.</p>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

function ResultCard({ payment, onAnother }: { payment: Payment; onAnother: () => void }) {
  const info = STATUS_INFO[payment.status as PaymentStatus];
  const tone = info.tone === "good" ? "good" : info.tone === "bad" ? "bad" : info.tone === "warn" ? "warn" : "";
  return (
    <div className="stack" style={{ maxWidth: 640 }}>
      <Card className={`result ${tone}`}>
        <div className="row-between">
          <h1 style={{ margin: 0 }}>{payment.status === "COMPLETED" ? "Payment sent" : payment.status === "SUBMITTED" ? "Payout submitted" : payment.status === "PENDING_REVIEW" ? "Held for review" : "Payment not sent"}</h1>
          <StatusBadge status={payment.status} />
        </div>
        <p style={{ marginTop: "0.75rem" }}>{info.explain}</p>
        <dl className="kv">
          <dt>Amount</dt><dd><Money amount={payment.amount} currency={payment.currency} /></dd>
          {payment.failureReason && (<><dt>Reason</dt><dd>{reasonText(payment.failureReason)}</dd></>)}
        </dl>
        <div className="row" style={{ marginTop: "1rem" }}>
          <Link className="btn btn-primary" href={`/payments/${payment.id}`}>View payment</Link>
          <Button variant="secondary" onClick={onAnother}>Send another</Button>
        </div>
      </Card>
    </div>
  );
}
