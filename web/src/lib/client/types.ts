/** Shapes returned by the PayNexa services (see docs/ARCHITECTURE.md). Money is always a decimal string. */

export type PaymentStatus =
  | "PENDING_REVIEW"
  | "COMPLETED"
  | "SUBMITTED"
  | "SETTLED"
  | "FAILED"
  | "REJECTED"
  | "REVERSED";

export type PaymentKind = "TRANSFER" | "PAYOUT" | "FUNDING";

export interface Account {
  id: string;
  currency: string;
  status: "ACTIVE" | "FROZEN";
  balance: string;
}

export interface Payment {
  id: string;
  kind: PaymentKind;
  status: PaymentStatus;
  fromAccountId: string;
  toAccountId: string | null;
  amount: string;
  currency: string;
  reference: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HistoryEntry {
  from: string | null;
  to: string;
  at: string;
}

export interface NotificationItem {
  id: string;
  paymentId: string;
  kind: string;
  message: string;
  createdAt: string;
  read: boolean;
}

export interface Finding {
  check: string;
  severity: "CRITICAL" | "WARNING";
  subject: string;
  detail: string;
}

export interface ReconciliationReport {
  runId: string;
  clean: boolean;
  findings: Finding[];
}

export interface DeadLetter {
  id: number;
  topic: string;
  key: string | null;
  payload: string;
  error: string | null;
  status: string;
  createdAt: string;
}

export interface SessionInfo {
  authenticated: boolean;
  userId: string | null;
  roles: string[];
  /** The email used to sign in on this browser; display only. */
  email?: string | null;
}
export interface StatsBucket {
  date: string; // YYYY-MM-DD, UTC day
  currency: string;
  incoming: string;
  outgoing: string;
  count: number;
}
export interface StatsTotal {
  currency: string;
  incoming: string;
  outgoing: string;
  count: number;
}
export interface Stats {
  days: number;
  from: string;
  to: string;
  buckets: StatsBucket[];
  totals: StatsTotal[];
  previousTotals: StatsTotal[];
}
export type StatsDays = 7 | 30 | 90 | 365;
export interface PaymentFilters {
  limit?: number;
  offset?: number;
  status?: PaymentStatus;
  kind?: PaymentKind;
  direction?: "in" | "out";
  from?: string; // YYYY-MM-DD, inclusive, UTC
  to?: string;
  accountId?: string;
  q?: string;
}

export const CURRENCIES = ["USD", "EUR", "GBP", "JPY", "INR", "CAD", "AUD", "CHF"] as const;
export type Currency = (typeof CURRENCIES)[number];

export const TERMINAL_STATUSES: readonly PaymentStatus[] = ["COMPLETED", "SETTLED", "FAILED", "REJECTED", "REVERSED"];
