"use client";
import type { ApiError } from "@/lib/client/api";
import { maskId } from "@/lib/client/format";
import { Alert, Button, CurrencyAmount, Dialog, ErrorNotice } from "../ui";

export interface TransferDraft {
  mode: "transfer" | "payout";
  fromCurrency: string;
  fromId: string;
  target: string;
  amount: string;
  reference: string;
  balanceAfter: string | null;
}

/**
 * The last checkpoint before money moves. It repeats exactly what will be sent, says what happens next, and requires
 * an explicit click. While the request is in flight it cannot be dismissed, so the outcome is never ambiguous.
 */
export function ConfirmTransferDialog({ open, draft, busy, error, onConfirm, onCancel }: {
  open: boolean; draft: TransferDraft; busy: boolean; error: ApiError | null; onConfirm: () => void; onCancel: () => void;
}) {
  const payout = draft.mode === "payout";
  return (
    <Dialog open={open} onClose={onCancel} dismissable={!busy} title="Review and confirm"
      description={payout ? "This payout leaves PayNexa through the simulated rail." : "This transfer moves money between PayNexa accounts."}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Back</Button>
          <Button onClick={onConfirm} busy={busy}>{busy ? "Sending…" : "Confirm and send"}</Button>
        </>
      }>
      <div className="confirm-amount">
        <span className="muted small">You send</span>
        <CurrencyAmount amount={draft.amount} currency={draft.fromCurrency} size="lg" />
      </div>
      <dl className="kv">
        <dt>Type</dt><dd>{payout ? "Payout (simulated rail)" : "Transfer"}</dd>
        <dt>From</dt><dd>{draft.fromCurrency} account <span className="num">{maskId(draft.fromId)}</span></dd>
        <dt>{payout ? "Destination" : "To account"}</dt><dd><code className="break">{draft.target}</code></dd>
        <dt>Fee</dt><dd>None</dd>
        {draft.reference && (<><dt>Reference</dt><dd>{draft.reference}</dd></>)}
        {draft.balanceAfter !== null && (<><dt>Balance after</dt><dd className="num">{draft.balanceAfter}</dd></>)}
      </dl>
      {payout && <Alert tone="info">A payout is in flight until the rail confirms it. The money leaves your balance now but is not settled yet.</Alert>}
      {error && <ErrorNotice error={error} />}
      <p className="muted small">If your connection drops you can press confirm again: the same request is never applied twice.</p>
    </Dialog>
  );
}
