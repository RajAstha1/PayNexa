"use client";
import Link from "next/link";
import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type CSSProperties, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { ApiError } from "@/lib/client/api";
import { STATUS_INFO, errorText, statusLabel, type Tone } from "@/lib/client/labels";
import { formatMoney } from "@/lib/client/money";
import type { PaymentStatus } from "@/lib/client/types";
import { Icon, type IconName } from "./icons";

/* ------------------------------------------------------------------------------------------------ basics */

export function Spinner({ label = "Loading" }: { label?: string }) {
  return <span className="spinner" role="status" aria-label={label} />;
}

export function Card({ title, actions, children, className = "", flush = false, id }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; flush?: boolean; id?: string }) {
  return (
    <section id={id} className={`card${flush ? " card-flush" : ""} ${className}`}>
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

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="page-head-actions">{actions}</div>}
    </div>
  );
}

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

/** A status as a small coloured dot plus a word; colour is never the only signal. */
export function StatusIndicator({ status }: { status: string }) {
  const info = STATUS_INFO[status as PaymentStatus];
  return (
    <span className={`status status-${info?.tone ?? "neutral"}`} title={info?.explain}>
      <span className="status-dot" aria-hidden="true" />
      {statusLabel(status)}
    </span>
  );
}
export const StatusBadge = StatusIndicator;

export function Money({ amount, currency, className = "" }: { amount: string; currency: string; className?: string }) {
  return <span className={`money ${className}`}>{formatMoney(amount, currency)}</span>;
}

/** A large amount with the cents set smaller. `masked` swaps the digits for dots (the visibility toggle). */
export function CurrencyAmount({ amount, currency, masked = false, size = "md", className = "" }: {
  amount: string; currency: string; masked?: boolean; size?: "sm" | "md" | "lg" | "xl"; className?: string;
}) {
  const formatted = formatMoney(amount, currency);
  let whole = formatted;
  let fraction = "";
  try {
    const parts = new Intl.NumberFormat("en-US", { style: "currency", currency }).formatToParts(amount as unknown as number);
    const idx = parts.findIndex((p) => p.type === "decimal");
    if (idx >= 0) {
      fraction = parts.slice(idx).map((p) => p.value).join("");
      whole = formatted.slice(0, formatted.length - fraction.length);
    }
  } catch {
    /* fall back to the plain string */
  }
  if (masked) {
    return (
      <span className={`amount amount-${size} ${className}`} aria-label="Balance hidden">
        <span aria-hidden="true" className="amount-mask">{"\u2022\u2022\u2022\u2022\u2022\u2022"}</span>
      </span>
    );
  }
  return (
    <span className={`amount amount-${size} num ${className}`} aria-label={formatted}>
      <span aria-hidden="true">{whole}</span>
      {fraction && <span aria-hidden="true" className="amount-fraction">{fraction}</span>}
    </span>
  );
}

export function Alert({ tone = "bad", children, onClose }: { tone?: "bad" | "good" | "info" | "warn"; children: ReactNode; onClose?: () => void }) {
  const icon: IconName = tone === "good" ? "check" : tone === "bad" || tone === "warn" ? "alert" : "info";
  return (
    <div className={`alert alert-${tone}`} role={tone === "bad" ? "alert" : "status"}>
      <Icon name={icon} size={18} />
      <div className="alert-body">{children}</div>
      {onClose && (
        <button type="button" className="icon-btn icon-btn-sm" onClick={onClose} aria-label="Dismiss">
          <Icon name="close" size={16} />
        </button>
      )}
    </div>
  );
}

export function ErrorNotice({ error, onRetry }: { error: ApiError | Error; onRetry?: () => void }) {
  const api = error instanceof ApiError ? error : null;
  return (
    <Alert tone="bad">
      <strong>{api ? errorText(api.code, api.message) : error.message}</strong>
      {api?.correlationId && (
        <div className="muted small">
          Reference <code>{api.correlationId}</code>
        </div>
      )}
      {onRetry && <button type="button" className="link-btn" onClick={onRetry}>Try again</button>}
    </Alert>
  );
}

export function EmptyState({ title, children, action, icon = "inbox" }: { title: string; children?: ReactNode; action?: ReactNode; icon?: IconName }) {
  return (
    <div className="empty">
      <span className="empty-icon"><Icon name={icon} size={22} /></span>
      <p className="empty-title">{title}</p>
      {children && <p className="muted empty-text">{children}</p>}
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
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost" | "soft" | "ink" | "glass"; busy?: boolean }) {
  const { className, ...others } = rest;
  return (
    <button {...others} className={`btn btn-${variant} ${className ?? ""}`} disabled={rest.disabled || busy} aria-busy={busy || undefined}>
      {busy && <span className="spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}

export function LinkButton({ href, variant = "primary", children, className = "" }: { href: string; variant?: "primary" | "secondary" | "soft" | "ghost" | "ink" | "glass"; children: ReactNode; className?: string }) {
  return <Link href={href} className={`btn btn-${variant} ${className}`}>{children}</Link>;
}

export function Field({
  label, hint, error, children, htmlFor,
}: { label: string; hint?: string; error?: string | null; children: ReactNode; htmlFor: string }) {
  return (
    <div className={`field ${error ? "field-error" : ""}`}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? (
        <p className="field-msg" id={`${htmlFor}-error`}>
          <Icon name="alert" size={14} /> {error}
        </p>
      ) : (
        hint && <p className="field-hint" id={`${htmlFor}-hint`}>{hint}</p>
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

/** Two or more mutually exclusive choices, e.g. the chart period. */
export function Segmented<T extends string | number>({ label, options, value, onChange }: {
  label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={String(o.value)} type="button" className="segmented-item" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------ skeletons */

export function Skeleton({ width, height = 14, radius, className = "" }: { width?: number | string; height?: number | string; radius?: number | string; className?: string }) {
  const style: CSSProperties = { width, height, borderRadius: radius };
  return <span className={`skeleton ${className}`} style={style} aria-hidden="true" />;
}

/** Placeholder rows that match the transaction list layout, so the page does not jump when data arrives. */
export function TransactionSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="txn-list" role="status" aria-label="Loading transactions">
      {Array.from({ length: rows }, (_, i) => (
        <div className="txn-row" key={i} aria-hidden="true">
          <Skeleton width={40} height={40} radius={14} />
          <div className="txn-main"><Skeleton width="55%" height={14} /><Skeleton width="35%" height={12} /></div>
          <Skeleton width={72} height={14} />
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------ overlays */

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * A modal dialog: traps focus, closes on Escape and on a click outside, locks page scroll while open and puts
 * focus back where it was. Rendered into <body> so it is never clipped by a card.
 */
export function Dialog({ open, onClose, title, description, children, footer, dismissable = true, size = "md", placement = "center", bare = false }: {
  open: boolean; onClose: () => void; title: string; description?: string; children: ReactNode; footer?: ReactNode; dismissable?: boolean;
  size?: "sm" | "md" | "lg"; placement?: "center" | "left"; bare?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descId = useId();
  const onCloseRef = useRef(onClose);
  const dismissRef = useRef(dismissable);
  useEffect(() => {
    onCloseRef.current = onClose;
    dismissRef.current = dismissable;
  });
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const scrollbar = window.innerWidth - document.documentElement.clientWidth;
    const prevOverflow = document.body.style.overflow;
    const prevPadding = document.body.style.paddingRight;
    document.body.style.overflow = "hidden";
    if (scrollbar > 0) document.body.style.paddingRight = `${scrollbar}px`;
    const first = panel.current?.querySelector<HTMLElement>("[data-autofocus]") ?? panel.current?.querySelector<HTMLElement>(FOCUSABLE) ?? panel.current;
    first?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && dismissRef.current) {
        e.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (e.key !== "Tab" || !panel.current) return;
      const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement);
      if (items.length === 0) {
        e.preventDefault();
        return;
      }
      const firstEl = items[0]!;
      const lastEl = items[items.length - 1]!;
      if (e.shiftKey && document.activeElement === firstEl) {
        e.preventDefault();
        lastEl.focus();
      } else if (!e.shiftKey && document.activeElement === lastEl) {
        e.preventDefault();
        firstEl.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.body.style.overflow = prevOverflow;
      document.body.style.paddingRight = prevPadding;
      previous?.focus?.();
    };
  }, [open]);
  if (!open || typeof document === "undefined") return null;
  return createPortal(
    <div className={`dialog-backdrop dialog-backdrop-${placement}`} onMouseDown={(e) => { if (e.target === e.currentTarget && dismissable) onClose(); }}>
      <div ref={panel} className={`dialog dialog-${size} dialog-${placement}${bare ? " dialog-bare" : ""}`} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={description ? descId : undefined} tabIndex={-1}>
        <header className={bare ? "visually-hidden" : "dialog-head"}>
          <h2 id={titleId}>{title}</h2>
          {dismissable && !bare && (
            <button type="button" className="icon-btn" onClick={onClose} aria-label="Close dialog"><Icon name="close" size={18} /></button>
          )}
        </header>
        {description && <p className="muted dialog-desc" id={descId}>{description}</p>}
        <div className="dialog-body">{children}</div>
        {footer && <footer className="dialog-foot">{footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}

/** Calls `onDismiss` for a click outside `ref` or an Escape key press, while `active`. Used by dropdown menus. */
export function useDismiss(ref: React.RefObject<HTMLElement | null>, active: boolean, onDismiss: () => void): void {
  const cb = useRef(onDismiss);
  useEffect(() => {
    cb.current = onDismiss;
  });
  useEffect(() => {
    if (!active) return;
    const onPointer = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) cb.current();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") cb.current();
    };
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [active, ref]);
}

/** Previous / next for offset paging. `hasNext` comes from fetching one row more than the page size. */
export function Pager({ page, hasNext, onPrev, onNext, busy = false }: { page: number; hasNext: boolean; onPrev: () => void; onNext: () => void; busy?: boolean }) {
  return (
    <nav className="pager" aria-label="Pagination">
      <Button variant="secondary" className="btn-sm" onClick={onPrev} disabled={page === 0 || busy}><Icon name="chevron-left" size={16} /> Previous</Button>
      <span className="muted small" aria-live="polite">Page {page + 1}</span>
      <Button variant="secondary" className="btn-sm" onClick={onNext} disabled={!hasNext || busy}>Next <Icon name="chevron-right" size={16} /></Button>
    </nav>
  );
}
