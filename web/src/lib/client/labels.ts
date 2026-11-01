import type { PaymentStatus } from "./types";

export type Tone = "good" | "warn" | "bad" | "info" | "neutral";

export const STATUS_INFO: Record<PaymentStatus, { label: string; tone: Tone; explain: string }> = {
  COMPLETED: { label: "Completed", tone: "good", explain: "The money moved between the two accounts." },
  SETTLED: { label: "Settled", tone: "good", explain: "The payout rail confirmed delivery." },
  SUBMITTED: { label: "In flight", tone: "info", explain: "Funds left the account and the payout is waiting for the rail to confirm. It is not settled yet." },
  PENDING_REVIEW: { label: "Under review", tone: "warn", explain: "Held by a risk rule. No money has moved until an administrator approves it." },
  FAILED: { label: "Failed", tone: "bad", explain: "The payment did not go through. No money moved." },
  REJECTED: { label: "Rejected", tone: "bad", explain: "Blocked by a risk rule or an administrator. No money moved." },
  REVERSED: { label: "Reversed", tone: "neutral", explain: "The payment was undone by a compensating entry; the money went back." },
};

const REASONS: Record<string, string> = {
  INSUFFICIENT_FUNDS: "Insufficient funds",
  ACCOUNT_FROZEN: "The sending account is frozen",
  PAYEE_UNAVAILABLE: "The receiving account is unavailable",
  RISK_BLOCKED: "Blocked by a risk rule",
  REVIEW_REJECTED: "Rejected during review",
  REVERSED_BY_ADMIN: "Reversed by an administrator",
  RAIL_REJECTED: "The payout rail rejected it",
};

export function reasonText(code: string | null | undefined): string | null {
  if (!code) return null;
  return REASONS[code] ?? code.toLowerCase().replace(/_/g, " ");
}

export function statusLabel(status: string): string {
  return STATUS_INFO[status as PaymentStatus]?.label ?? status;
}

const KIND_LABELS: Record<string, string> = { TRANSFER: "Transfer", PAYOUT: "Payout", FUNDING: "Sandbox funding" };
export function kindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? kind;
}

/** Friendly text for the codes the backend returns. Unknown codes fall back to the server's own message. */
const ERROR_TEXT: Record<string, string> = {
  INVALID_CREDENTIALS: "That email and password do not match.",
  EMAIL_TAKEN: "An account with this email already exists.",
  WEAK_PASSWORD: "Choose a longer, less guessable password (at least 12 characters).",
  INVALID_EMAIL: "Enter a valid email address.",
  RATE_LIMITED: "Too many attempts. Wait a moment and try again.",
  ACCOUNT_NOT_FOUND: "That account was not found.",
  PAYEE_NOT_FOUND: "The receiving account was not found.",
  SAME_ACCOUNT: "Choose a different receiving account.",
  CURRENCY_MISMATCH: "The currency must match both accounts.",
  IDEMPOTENCY_KEY_REUSED: "This request was already submitted with different details. Start a new payment.",
  SELF_APPROVAL_NOT_ALLOWED: "You cannot approve a payment you created.",
  INSUFFICIENT_FUNDS_FOR_REVERSAL: "The receiver has already spent the money, so it cannot be reversed.",
  INVALID_STATE_TRANSITION: "That action is no longer possible for this payment. Refresh and check its status.",
  BACKEND_UNAVAILABLE: "The service is temporarily unavailable. Please try again.",
  TEMPORARILY_UNAVAILABLE: "The service is temporarily unavailable. Retry with the same request.",
  INVALID_DESTINATION: "The destination contains unsupported characters.",
};

export function errorText(code: string, fallback: string): string {
  return ERROR_TEXT[code] ?? fallback;
}

export type NotificationCategory = "payments" | "review" | "payouts" | "issues";
export const NOTIFICATION_CATEGORIES: { id: NotificationCategory; label: string }[] = [
  { id: "payments", label: "Payments" },
  { id: "review", label: "Review" },
  { id: "payouts", label: "Payouts" },
  { id: "issues", label: "Issues" },
];
const NOTIFICATION_INFO: Record<string, { category: NotificationCategory; title: string; tone: Tone }> = {
  PAYMENT_SENT: { category: "payments", title: "Payment sent", tone: "info" },
  PAYMENT_RECEIVED: { category: "payments", title: "Payment received", tone: "good" },
  PAYMENT_UNDER_REVIEW: { category: "review", title: "Held for review", tone: "warn" },
  PAYOUT_SUBMITTED: { category: "payouts", title: "Payout submitted", tone: "info" },
  PAYOUT_SETTLED: { category: "payouts", title: "Payout settled", tone: "good" },
  PAYMENT_REJECTED: { category: "issues", title: "Payment rejected", tone: "bad" },
  PAYMENT_FAILED: { category: "issues", title: "Payment failed", tone: "bad" },
  PAYMENT_REVERSED: { category: "issues", title: "Payment reversed", tone: "neutral" },
};
export function notificationInfo(kind: string): { category: NotificationCategory; title: string; tone: Tone } {
  return NOTIFICATION_INFO[kind] ?? { category: "payments", title: kind.toLowerCase().replace(/_/g, " "), tone: "neutral" };
}
