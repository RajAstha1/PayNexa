"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { currencyName } from "@/lib/client/format";
import { formatSigned } from "@/lib/client/money";
import { percentChange, summarize } from "@/lib/client/analytics";
import type { Account, Stats } from "@/lib/client/types";
import { Icon } from "../icons";
import { CurrencyAmount, LinkButton, Skeleton } from "../ui";

const HIDE_KEY = "pn_hide_balance";

/** The visibility preference is a harmless UI setting, so it lives in localStorage (never an amount). */
export function useBalanceHidden(): [boolean, (v: boolean) => void] {
  const [hidden, setHidden] = useState(false);
  useEffect(() => {
    try {
      // Reading storage needs the browser, so it happens after mount rather than during render.
      setHidden(localStorage.getItem(HIDE_KEY) === "1"); // eslint-disable-line react-hooks/set-state-in-effect
    } catch { /* storage unavailable: default to visible */ }
  }, []);
  return [hidden, (v) => {
    setHidden(v);
    try { localStorage.setItem(HIDE_KEY, v ? "1" : "0"); } catch { /* ignore */ }
  }];
}

function Waves() {
  return (
    <svg className="balance-waves" viewBox="0 0 520 280" preserveAspectRatio="xMaxYMid slice" aria-hidden="true" focusable="false">
      <path d="M520 20C440 10 380 70 330 110S210 190 120 170 20 260 0 280H520Z" fill="#fff" opacity="0.18" />
      <path d="M520 80C450 70 400 120 360 160S260 230 190 220 90 280 70 280H520Z" fill="#fff" opacity="0.2" />
      <path d="M520 150C470 140 430 180 400 210S330 270 290 280H520Z" fill="#7b6bea" opacity="0.28" />
      <circle cx="450" cy="62" r="46" fill="#fff" opacity="0.14" />
    </svg>
  );
}

export function BalanceCard({ accounts, selected, onSelect, stats, statsLoading, isAdmin }: {
  accounts: Account[]; selected: string; onSelect: (currency: string) => void; stats: Stats | null; statsLoading: boolean; isAdmin: boolean;
}) {
  const [hidden, setHidden] = useBalanceHidden();
  const account = accounts.find((a) => a.currency === selected) ?? accounts[0];
  if (!account) {
    return (
      <section className="balance-card" aria-label="Balance">
        <Waves />
        <div className="balance-body">
          <span className="balance-label">Available balance</span>
          <p className="balance-empty">Open your first account to start sending and receiving synthetic funds.</p>
          <div className="row"><LinkButton href="/accounts" variant="primary">Open an account</LinkButton></div>
        </div>
      </section>
    );
  }
  const week = stats && stats.days === 7 ? summarize(stats.totals, account.currency) : null;
  const previous = stats && stats.days === 7 ? summarize(stats.previousTotals, account.currency) : null;
  const change = week && previous ? percentChange(week.net, previous.net) : null;
  return (
    <section className="balance-card" aria-label="Balance">
      <Waves />
      <div className="balance-body">
        <div className="balance-top">
          <span className="balance-label">Available balance · {currencyName(account.currency)}</span>
          <button type="button" className="icon-btn icon-btn-glass" aria-pressed={hidden} aria-label={hidden ? "Show balance" : "Hide balance"} onClick={() => setHidden(!hidden)}>
            <Icon name={hidden ? "eye-off" : "eye"} size={18} />
          </button>
        </div>
        <CurrencyAmount amount={account.balance} currency={account.currency} masked={hidden} size="xl" />
        <div className="balance-meta" aria-live="polite">
          {statsLoading && !week ? (
            <Skeleton width={190} height={14} />
          ) : week && week.count > 0 ? (
            <>
              <span className={`trend ${week.net >= 0n ? "trend-up" : "trend-down"}`}>
                <Icon name={week.net >= 0n ? "arrow-up-right" : "arrow-down-left"} size={14} />
                {hidden ? "\u2022\u2022\u2022\u2022" : formatSigned(week.net, account.currency)}
              </span>
              <span>net movement, last 7 days</span>
              {change !== null && !hidden && <span className="balance-compare">{change >= 0 ? "+" : "\u2212"}{Math.abs(change).toFixed(1)}% vs the 7 days before</span>}
            </>
          ) : week ? (
            <span>No money moved in the last 7 days</span>
          ) : null}
        </div>
        <div className="balance-actions">
          {account.status === "ACTIVE" ? <LinkButton href={`/send?from=${account.id}`} variant="navy"><Icon name="send" size={18} /> Send Money</LinkButton> : <span className="badge badge-bad">Account frozen</span>}
          {isAdmin && <LinkButton href="/admin?tab=accounts" variant="glass"><Icon name="plus" size={18} /> Add Funds</LinkButton>}
          <Link href={`/accounts/${account.id}`} className="balance-link">Account details <Icon name="arrow-right" size={16} /></Link>
        </div>
        {accounts.length > 1 && (
          <div className="balance-chips" role="group" aria-label="Currency shown">
            {accounts.map((a) => (
              <button key={a.id} type="button" className="chip" aria-pressed={a.currency === account.currency} onClick={() => onSelect(a.currency)}>{a.currency}</button>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
