"use client";
import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useState } from "react";
import { api } from "@/lib/client/api";
import { currencyName, formatDateTime, maskId } from "@/lib/client/format";
import { formatMoney } from "@/lib/client/money";
import { describe } from "@/lib/client/transactions";
import { useAsync } from "@/lib/client/use-async";
import { Icon, type IconName } from "../icons";
import { Dialog } from "../ui";
import { visibleNav } from "./nav";
import { useAuth } from "../auth-provider";

interface Result {
  id: string;
  group: "Pages" | "Accounts" | "Payments" | "Search";
  icon: IconName;
  title: string;
  hint: string;
  href: string;
}

/**
 * Command palette over data the user already has: pages, their accounts and their latest payments. It does not look
 * up other people (the services expose no user directory, by design). "Search all payments" hands the text to the
 * server-side search on the Payments page.
 */
export function SearchCommand({ open, onClose }: { open: boolean; onClose: () => void }) {
  const router = useRouter();
  const auth = useAuth();
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const listId = useId();
  const accounts = useAsync((s) => api.accounts(s), [open], { enabled: open });
  const payments = useAsync((s) => api.payments({ limit: 30 }, s), [open], { enabled: open });

  useEffect(() => {
    if (!open) {
      setQuery(""); // eslint-disable-line react-hooks/set-state-in-effect -- reset the palette each time it closes
      setCursor(0);
    }
  }, [open]);

  const results = useMemo<Result[]>(() => {
    const q = query.trim().toLowerCase();
    const mine = new Set((accounts.data ?? []).map((a) => a.id));
    const out: Result[] = [];
    for (const p of visibleNav(auth.isAdmin)) {
      if (!q || p.label.toLowerCase().includes(q)) out.push({ id: `page-${p.href}`, group: "Pages", icon: p.icon, title: p.label, hint: "Go to page", href: p.href });
    }
    for (const a of accounts.data ?? []) {
      const hay = `${a.currency} ${currencyName(a.currency)} ${a.id}`.toLowerCase();
      if (!q || hay.includes(q)) out.push({ id: `acct-${a.id}`, group: "Accounts", icon: "accounts", title: `${a.currency} account ${maskId(a.id)}`, hint: formatMoney(a.balance, a.currency), href: `/accounts/${a.id}` });
    }
    if (q) {
      for (const p of payments.data ?? []) {
        const d = describe(p, mine);
        const hay = `${d.title} ${d.subtitle} ${p.id} ${p.amount} ${p.currency} ${p.status}`.toLowerCase();
        if (hay.includes(q)) out.push({ id: `pay-${p.id}`, group: "Payments", icon: "payments", title: d.title, hint: `${formatMoney(p.amount, p.currency)} · ${formatDateTime(p.createdAt)}`, href: `/payments/${p.id}` });
        if (out.length > 40) break;
      }
      out.push({ id: "search-all", group: "Search", icon: "search", title: `Search all payments for \u201c${query.trim()}\u201d`, hint: "Reference or payment id", href: `/payments?q=${encodeURIComponent(query.trim().slice(0, 50))}` });
    }
    return out.slice(0, 40);
  }, [query, accounts.data, payments.data, auth.isAdmin]);

  const active = Math.min(cursor, Math.max(results.length - 1, 0));
  const go = (r: Result | undefined) => {
    if (!r) return;
    onClose();
    router.push(r.href);
  };

  return (
    <Dialog open={open} onClose={onClose} title="Search" size="lg" bare>
      <div className="palette">
        <div className="palette-input">
          <Icon name="search" size={20} />
          <input
            data-autofocus
            role="combobox"
            aria-expanded="true"
            aria-controls={listId}
            aria-activedescendant={results[active] ? `${listId}-${results[active].id}` : undefined}
            aria-label="Search accounts, payments and pages"
            placeholder="Search accounts, payments, pages…"
            autoComplete="off"
            spellCheck={false}
            value={query}
            maxLength={50}
            onChange={(e) => { setQuery(e.target.value); setCursor(0); }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") { e.preventDefault(); setCursor(Math.min(active + 1, results.length - 1)); }
              else if (e.key === "ArrowUp") { e.preventDefault(); setCursor(Math.max(active - 1, 0)); }
              else if (e.key === "Enter") { e.preventDefault(); go(results[active]); }
            }}
          />
          <kbd className="kbd">esc</kbd>
        </div>
        <ul id={listId} role="listbox" aria-label="Results" className="palette-list">
          {results.length === 0 && <li className="palette-empty muted" role="presentation">Nothing matches that.</li>}
          {results.map((r, i) => (
            <li key={r.id} id={`${listId}-${r.id}`} role="option" aria-selected={i === active}
              className={`palette-item${i === active ? " active" : ""}`} onMouseMove={() => setCursor(i)} onClick={() => go(r)}>
              <span className="palette-icon"><Icon name={r.icon} size={18} /></span>
              <span className="palette-title">{r.title}</span>
              <span className="palette-group">{r.group}</span>
              <span className="palette-hint muted small">{r.hint}</span>
            </li>
          ))}
        </ul>
        <p className="palette-foot faint small">↑ ↓ to move · Enter to open · {payments.fetching ? "Loading recent payments…" : "Searches your accounts and 30 latest payments"}</p>
      </div>
    </Dialog>
  );
}
