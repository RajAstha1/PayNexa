"use client";
import Link from "next/link";
import { countryFor, currencyName, currencySymbol, maskId } from "@/lib/client/format";
import type { Account } from "@/lib/client/types";
import { Icon } from "../icons";
import { CurrencyAmount } from "../ui";

export function AccountSummaryCard({ account, masked = false }: { account: Account; masked?: boolean }) {
  return (
    <Link href={`/accounts/${account.id}`} className="acct-card" aria-label={`${account.currency} account, ${maskId(account.id)}`}>
      <span className="acct-top">
        <span className="acct-symbol" aria-hidden="true">{currencySymbol(account.currency)}</span>
        <span className="acct-id">
          <strong>{account.currency}</strong>
          <span className="muted small">{currencyName(account.currency)}</span>
        </span>
        <span className="country-badge" title={`${currencyName(account.currency)} region`}>{countryFor(account.currency)}</span>
      </span>
      <span className="acct-balance">
        <span className="muted small">Available</span>
        <CurrencyAmount amount={account.balance} currency={account.currency} masked={masked} size="md" />
      </span>
      <span className="acct-bottom">
        <span className="muted small num">{maskId(account.id)}</span>
        {account.status === "FROZEN" && <span className="badge badge-bad">Frozen</span>}
        <span className="acct-arrow" aria-hidden="true"><Icon name="arrow-up-right" size={16} /></span>
      </span>
    </Link>
  );
}
