"use client";

import { useState, type ButtonHTMLAttributes, type ReactNode } from "react";
import { ApiError } from "@/lib/client/api";
import { STATUS_INFO, errorText, statusLabel, type Tone } from "@/lib/client/labels";
import { formatMoney } from "@/lib/client/money";
import type { PaymentStatus } from "@/lib/client/types";

export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <span className="spinner" role="status" aria-label={label}>
      <span className="spinner-dot" aria-hidden="true" />
    </span>
  );
}

export function Card({ title, actions, children, className = "" }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function StatusBadge({ status }: { status: string }) {
  const info = STATUS_INFO[status as PaymentStatus];
  return (
    <span title={info?.explain}>
      <Badge tone={info?.tone ?? "neutral"}>{statusLabel(status)}</Badge>
    </span>
  );
}

export function Money({ amount, currency, className = "" }: { amount: string; currency: string; className?: string }) {
  return <span className={`money ${className}`}>{formatMoney(amount, currency)}</span>;
}

export function Alert({ tone = "bad", children, onClose }: { tone?: "bad" | "good" | "info" | "warn"; children: ReactNode; onClose?: () => void }) {
  return (
    <div className={`alert alert-${tone}`} role={tone === "bad" ? "alert" : "status"}>
      <div>{children}</div>
      {onClose && (
        <button type="button" className="alert-close" onClick={onClose} aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  );
}

export function ErrorNotice({ error }: { error: ApiError | Error }) {
  const api = error instanceof ApiError ? error : null;
  return (
    <Alert tone="bad">
      <strong>{api ? errorText(api.code, api.message) : error.message}</strong>
      {api?.correlationId && (
        <div className="muted small">
          Reference <code>{api.correlationId}</code>
        </div>
      )}
    </Alert>
  );
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <p className="muted">{children}</p>}
      {action}
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div className="loading">
      <Spinner label={label} /> <span className="muted">{label}…</span>
    </div>
  );
}

export function Button({
  variant = "primary",
  busy = false,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost"; busy?: boolean }) {
  return (
    <button {...rest} className={`btn btn-${variant} ${rest.className ?? ""}`} disabled={rest.disabled || busy} aria-busy={busy || undefined}>
      {busy && <Spinner label="Working" />}
      {children}
    </button>
  );
}

export function Field({
  label,
  hint,
  error,
  children,
  htmlFor,
}: {
  label: string;
  hint?: string;
  error?: string | null;
  children: ReactNode;
  htmlFor: string;
}) {
  return (
    <div className={`field ${error ? "field-error" : ""}`}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? (
        <p className="field-msg" id={`${htmlFor}-error`}>
          {error}
        </p>
      ) : (
        hint && (
          <p className="field-hint" id={`${htmlFor}-hint`}>
            {hint}
          </p>
        )
      )}
    </div>
  );
}

/** Shows a long id in short form with a copy button. */
export function ShortId({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <span className="shortid">
      <code title={value}>{value.slice(0, 8)}…</code>
      <button
        type="button"
        className="link-btn"
        aria-label={`Copy id ${value}`}
        onClick={() => {
          void navigator.clipboard?.writeText(value).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          });
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </span>
  );
}

export function when(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
