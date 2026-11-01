import type {
  Account, DeadLetter, HistoryEntry, NotificationItem, Payment, ReconciliationReport, SessionInfo,
} from "./types";

/** An error the UI can show: stable code from the backend plus a human message that is safe to display. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId?: string,
  ) {
    super(message);
  }
}

type Listener = () => void;
let onSessionEnded: Listener | null = null;

/** The auth provider registers this so any API call that discovers the session is over can redirect to sign-in. */
export function setSessionEndedHandler(listener: Listener | null): void {
  onSessionEnded = listener;
}

interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

async function request<T>(url: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(url, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      credentials: "same-origin",
      cache: "no-store",
      signal: opts.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK", "Cannot reach the server. Check your connection and try again.");
  }

  const text = res.status === 204 ? "" : await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!res.ok) {
    const problem = (data ?? {}) as { code?: string; detail?: string; correlationId?: string };
    if (res.status === 401 && problem.code === "SESSION_ENDED") onSessionEnded?.();
    throw new ApiError(
      res.status,
      problem.code ?? `HTTP_${res.status}`,
      problem.detail ?? "Something went wrong. Please try again.",
      problem.correlationId ?? res.headers.get("x-correlation-id") ?? undefined,
    );
  }
  return data as T;
}

const payments = (path: string) => `/api/backend/payments/${path}`;
const notifications = (path: string) => `/api/backend/notifications/${path}`;

export const api = {
  // --- authentication (handled by the BFF; tokens never reach this code) ---
  session: (signal?: AbortSignal) => request<SessionInfo>("/api/auth/session", { signal }),
  login: (email: string, password: string) => request<SessionInfo>("/api/auth/login", { method: "POST", body: { email, password } }),
  register: (email: string, password: string) => request<SessionInfo>("/api/auth/register", { method: "POST", body: { email, password } }),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),

  // --- accounts and payments ---
  accounts: (signal?: AbortSignal) => request<Account[]>(payments("accounts"), { signal }),
  openAccount: (currency: string) => request<Account>(payments("accounts"), { method: "POST", body: { currency } }),
  account: (id: string, signal?: AbortSignal) => request<Account>(payments(`accounts/${id}`), { signal }),
  payments: (limit = 50, signal?: AbortSignal) => request<Payment[]>(payments(`payments?limit=${limit}`), { signal }),
  payment: (id: string, signal?: AbortSignal) => request<Payment>(payments(`payments/${id}`), { signal }),
  paymentHistory: (id: string, signal?: AbortSignal) => request<HistoryEntry[]>(payments(`payments/${id}/history`), { signal }),
  transfer: (idempotencyKey: string, body: { fromAccountId: string; toAccountId: string; amount: string; currency: string; reference?: string }) =>
    request<Payment>(payments("payments/transfers"), { method: "POST", body, idempotencyKey }),
  payout: (idempotencyKey: string, body: { fromAccountId: string; destination: string; amount: string; currency: string; reference?: string }) =>
    request<Payment>(payments("payments/payouts"), { method: "POST", body, idempotencyKey }),

  // --- notifications ---
  notifications: (signal?: AbortSignal) => request<NotificationItem[]>(notifications("notifications"), { signal }),
  markRead: (id: string) => request<void>(notifications(`notifications/${id}/read`), { method: "POST" }),

  // --- administration (the services enforce ROLE_ADMIN; the UI only hides the menu) ---
  pendingReview: (signal?: AbortSignal) => request<Payment[]>(payments("admin/payments/pending-review"), { signal }),
  approve: (id: string) => request<Payment>(payments(`admin/payments/${id}/approve`), { method: "POST" }),
  reject: (id: string, reason?: string) => request<Payment>(payments(`admin/payments/${id}/reject`), { method: "POST", body: { reason } }),
  reverse: (id: string, reason?: string) => request<Payment>(payments(`admin/payments/${id}/reverse`), { method: "POST", body: { reason } }),
  fund: (idempotencyKey: string, accountId: string, amount: string, currency: string) =>
    request<Payment>(payments(`admin/accounts/${accountId}/fund`), { method: "POST", body: { amount, currency }, idempotencyKey }),
  setAccountStatus: (accountId: string, status: "ACTIVE" | "FROZEN", reason?: string) =>
    request<Account>(payments(`admin/accounts/${accountId}/status`), { method: "POST", body: { status, reason } }),
  runReconciliation: () => request<ReconciliationReport>(payments("admin/reconciliation/run"), { method: "POST" }),
  requeueDeadOutbox: () => request<{ requeued: number }>(payments("admin/outbox/requeue-dead"), { method: "POST" }),
  deadLetters: (signal?: AbortSignal) => request<DeadLetter[]>(notifications("admin/dead-letters"), { signal }),
  replayDeadLetter: (id: number) => request<{ replayed: number }>(notifications(`admin/dead-letters/${id}/replay`), { method: "POST" }),
};

/** A fresh idempotency key. Generated once per user intent (form open), reused on retries of that same intent. */
export function newIdempotencyKey(prefix = "web"): string {
  return `${prefix}-${crypto.randomUUID()}`;
}
