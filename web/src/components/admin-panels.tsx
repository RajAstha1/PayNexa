"use client";

import Link from "next/link";
import { useRef, useState, type FormEvent } from "react";
import { ApiError, api, newIdempotencyKey } from "@/lib/client/api";
import { checkAmount } from "@/lib/client/money";
import { CURRENCIES } from "@/lib/client/types";
import type { ReconciliationReport } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";
import { Alert, Badge, Button, Card, EmptyState, ErrorNotice, Field, Loading, Money, ShortId, StatusBadge, when } from "./ui";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A two-step button: the first click asks, the second performs. Used for actions that move money or end a payment. */
function ConfirmButton({ label, confirmLabel, variant = "secondary", busy, onConfirm }: {
  label: string; confirmLabel: string; variant?: "secondary" | "danger" | "primary"; busy?: boolean; onConfirm: () => void;
}) {
  const [asking, setAsking] = useState(false);
  if (!asking) return <Button variant={variant} className="btn-sm" onClick={() => setAsking(true)} disabled={busy}>{label}</Button>;
  return (
    <span className="row" style={{ gap: "0.35rem" }}>
      <Button variant={variant === "secondary" ? "primary" : variant} className="btn-sm" busy={busy} onClick={() => { setAsking(false); onConfirm(); }}>{confirmLabel}</Button>
      <Button variant="ghost" className="btn-sm" onClick={() => setAsking(false)}>Cancel</Button>
    </span>
  );
}

// ------------------------------------------------------------------------------------------------ review queue

export function ReviewQueue() {
  const queue = useAsync((s) => api.pendingReview(s), []);
  const [working, setWorking] = useState<string | null>(null);
  const [message, setMessage] = useState<{ tone: "good" | "bad"; text: string } | null>(null);

  async function act(id: string, action: "approve" | "reject") {
    setWorking(id);
    setMessage(null);
    try {
      const result = action === "approve" ? await api.approve(id) : await api.reject(id, "Rejected in review");
      setMessage({ tone: "good", text: `Payment ${id.slice(0, 8)}… is now ${result.status.toLowerCase().replace(/_/g, " ")}.` });
      queue.reload();
    } catch (e) {
      setMessage({ tone: "bad", text: e instanceof ApiError ? friendly(e) : "The action failed." });
    } finally {
      setWorking(null);
    }
  }

  return (
    <Card title="Payments waiting for review" actions={<Button variant="secondary" className="btn-sm" onClick={queue.reload}>Refresh</Button>}>
      {message && <div style={{ marginBottom: "1rem" }}><Alert tone={message.tone} onClose={() => setMessage(null)}>{message.text}</Alert></div>}
      {queue.loading && <Loading label="Loading review queue" />}
      {queue.error && <ErrorNotice error={queue.error} />}
      {queue.data?.length === 0 && <EmptyState title="The queue is empty">Payments held by a risk rule appear here.</EmptyState>}
      {queue.data && queue.data.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Created</th><th>Type</th><th>Payment</th><th>Status</th><th className="right">Amount</th><th className="right">Decision</th></tr></thead>
            <tbody>
              {queue.data.map((p) => (
                <tr key={p.id}>
                  <td>{when(p.createdAt)}</td>
                  <td>{p.kind === "PAYOUT" ? "Payout" : "Transfer"}</td>
                  <td><Link href={`/payments/${p.id}`}>{p.id.slice(0, 8)}…</Link></td>
                  <td><StatusBadge status={p.status} /></td>
                  <td className="right"><Money amount={p.amount} currency={p.currency} /></td>
                  <td className="right">
                    <span className="row" style={{ justifyContent: "flex-end" }}>
                      <ConfirmButton label="Approve" confirmLabel="Approve and move money" variant="primary" busy={working === p.id} onConfirm={() => void act(p.id, "approve")} />
                      <ConfirmButton label="Reject" confirmLabel="Reject payment" variant="danger" busy={working === p.id} onConfirm={() => void act(p.id, "reject")} />
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small" style={{ marginTop: "1rem" }}>
        Four-eyes rule: you cannot approve a payment you created yourself. Approval re-checks funds and account status at that moment.
      </p>
    </Card>
  );
}

// ------------------------------------------------------------------------------------------------ accounts

export function AdminAccounts() {
  const [accountId, setAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<{ tone: "good" | "bad"; text: string } | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const fundKey = useRef<{ fp: string; key: string } | null>(null);

  function validId(): boolean {
    const ok = UUID.test(accountId.trim());
    setErrors((e) => ({ ...e, accountId: ok ? "" : "Enter a valid account ID (UUID)" }));
    return ok;
  }

  async function fund(e: FormEvent) {
    e.preventDefault();
    const check = checkAmount(amount, currency);
    setErrors({ amount: check.ok ? "" : check.message });
    if (!validId() || !check.ok) return;
    const fp = [accountId.trim(), amount.trim(), currency].join("|");
    if (fundKey.current?.fp !== fp) fundKey.current = { fp, key: newIdempotencyKey("fund") };
    setBusy("fund");
    setResult(null);
    try {
      const p = await api.fund(fundKey.current.key, accountId.trim().toLowerCase(), amount.trim(), currency);
      fundKey.current = null;
      setResult({ tone: "good", text: `Added ${p.amount} ${p.currency} of synthetic funds (${p.status.toLowerCase()}).` });
    } catch (err) {
      setResult({ tone: "bad", text: err instanceof ApiError ? (err.status === 404 ? "Sandbox funding is switched off on this deployment, or the account does not exist." : friendly(err)) : "Funding failed." });
    } finally {
      setBusy(null);
    }
  }

  async function setStatus(status: "ACTIVE" | "FROZEN") {
    if (!validId()) return;
    setBusy(status);
    setResult(null);
    try {
      const a = await api.setAccountStatus(accountId.trim().toLowerCase(), status, reason.trim() || undefined);
      setResult({ tone: "good", text: `Account ${a.id.slice(0, 8)}… is now ${a.status.toLowerCase()}.` });
    } catch (err) {
      setResult({ tone: "bad", text: err instanceof ApiError ? friendly(err) : "The change failed." });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="grid grid-2">
      <Card title="Sandbox funding">
        <form className="form" onSubmit={fund} noValidate>
          <Field label="Account ID" htmlFor="adm-account" error={errors.accountId}>
            <input id="adm-account" value={accountId} onChange={(e) => setAccountId(e.target.value)} spellCheck={false} autoComplete="off" placeholder="Account UUID" />
          </Field>
          <div className="form-row">
            <Field label="Amount" htmlFor="adm-amount" error={errors.amount}>
              <input id="adm-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoComplete="off" placeholder="100.00" />
            </Field>
            <Field label="Currency" htmlFor="adm-currency">
              <select id="adm-currency" value={currency} onChange={(e) => setCurrency(e.target.value)}>
                {CURRENCIES.map((c) => <option key={c}>{c}</option>)}
              </select>
            </Field>
          </div>
          <Button type="submit" busy={busy === "fund"}>Add synthetic funds</Button>
        </form>
      </Card>
      <Card title="Freeze or unfreeze">
        <div className="form">
          <p className="muted small">A frozen account cannot send or receive. Uses the account ID entered on the left.</p>
          <Field label="Reason (recorded in the audit log)" htmlFor="adm-reason">
            <input id="adm-reason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} autoComplete="off" />
          </Field>
          <div className="row">
            <ConfirmButton label="Freeze account" confirmLabel="Freeze now" variant="danger" busy={busy === "FROZEN"} onConfirm={() => void setStatus("FROZEN")} />
            <ConfirmButton label="Unfreeze" confirmLabel="Unfreeze now" busy={busy === "ACTIVE"} onConfirm={() => void setStatus("ACTIVE")} />
          </div>
        </div>
      </Card>
      {result && <div style={{ gridColumn: "1 / -1" }}><Alert tone={result.tone} onClose={() => setResult(null)}>{result.text}</Alert></div>}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ reconciliation

export function AdminReconciliation() {
  const [report, setReport] = useState<ReconciliationReport | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      setReport(await api.runReconciliation());
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Ledger reconciliation" actions={<Button onClick={() => void run()} busy={busy}>Run reconciliation</Button>}>
      <p className="muted">
        Re-derives the books from the immutable ledger and compares them with every balance, payment status and event. It reports problems; it never changes money.
      </p>
      {error && <ErrorNotice error={error} />}
      {report && (
        <>
          <Alert tone={report.clean ? "good" : "bad"}>
            {report.clean ? "Clean: the books are consistent." : `${report.findings.length} finding(s) need attention.`}
          </Alert>
          {report.findings.length > 0 && (
            <div className="table-wrap" style={{ marginTop: "1rem" }}>
              <table>
                <thead><tr><th>Severity</th><th>Check</th><th>Subject</th><th>Detail</th></tr></thead>
                <tbody>
                  {report.findings.map((f, i) => (
                    <tr key={i} className={`finding-${f.severity}`}>
                      <td><Badge tone={f.severity === "CRITICAL" ? "bad" : "warn"}>{f.severity}</Badge></td>
                      <td><code>{f.check}</code></td>
                      <td><code>{f.subject}</code></td>
                      <td>{f.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="muted small" style={{ marginTop: "0.75rem" }}>Run id <code>{report.runId}</code></p>
        </>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------------------------------------ operations

export function AdminOperations() {
  const dead = useAsync((s) => api.deadLetters(s), []);
  const [message, setMessage] = useState<{ tone: "good" | "bad"; text: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  async function requeue() {
    setBusy("outbox");
    try {
      const r = await api.requeueDeadOutbox();
      setMessage({ tone: "good", text: `${r.requeued} dead event(s) were put back in the outbox for publishing.` });
    } catch (e) {
      setMessage({ tone: "bad", text: e instanceof ApiError ? friendly(e) : "Requeue failed." });
    } finally {
      setBusy(null);
    }
  }

  async function replay(id: number) {
    setBusy(`dl-${id}`);
    try {
      await api.replayDeadLetter(id);
      setMessage({ tone: "good", text: `Dead letter ${id} was replayed. If it fails again it returns here as a new entry.` });
      dead.reload();
    } catch (e) {
      setMessage({ tone: "bad", text: e instanceof ApiError ? friendly(e) : "Replay failed." });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="stack">
      {message && <Alert tone={message.tone} onClose={() => setMessage(null)}>{message.text}</Alert>}
      <Card title="Outbox (payments-service → Kafka)">
        <p className="muted">Events that failed to publish after all retries are parked as dead. Fix the cause (usually the broker), then requeue them.</p>
        <ConfirmButton label="Requeue dead events" confirmLabel="Requeue all dead events" busy={busy === "outbox"} onConfirm={() => void requeue()} />
      </Card>
      <Card title="Dead letters (notification-service)" actions={<Button variant="secondary" className="btn-sm" onClick={dead.reload}>Refresh</Button>}>
        {dead.loading && <Loading label="Loading dead letters" />}
        {dead.error && <ErrorNotice error={dead.error} />}
        {dead.data?.length === 0 && <EmptyState title="No dead letters">Messages that could not be processed after retries appear here.</EmptyState>}
        {dead.data && dead.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>ID</th><th>Received</th><th>Key</th><th>Error</th><th className="right">Action</th></tr></thead>
              <tbody>
                {dead.data.map((d) => (
                  <tr key={d.id}>
                    <td>{d.id}</td>
                    <td>{when(d.createdAt)}</td>
                    <td>{d.key ? <ShortId value={d.key} /> : "—"}</td>
                    <td className="muted">{d.error ?? "—"}</td>
                    <td className="right"><ConfirmButton label="Replay" confirmLabel="Replay now" busy={busy === `dl-${d.id}`} onConfirm={() => void replay(d.id)} /></td>
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

function friendly(e: ApiError): string {
  const known: Record<string, string> = {
    SELF_APPROVAL_NOT_ALLOWED: "You cannot approve a payment you created yourself.",
    INVALID_STATE_TRANSITION: "That payment is no longer in a state where this action is possible. Refresh the list.",
    INSUFFICIENT_FUNDS_FOR_REVERSAL: "The receiver has already spent the money.",
    FORBIDDEN: "You do not have permission to do that.",
    ACCOUNT_NOT_FOUND: "That account does not exist.",
    PAYMENT_NOT_FOUND: "That payment does not exist.",
  };
  return known[e.code] ?? e.message;
}
