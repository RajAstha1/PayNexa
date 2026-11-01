"use client";
import Link from "next/link";
import { formatDateTime } from "@/lib/client/format";
import { formatMoney } from "@/lib/client/money";
import { kindLabel } from "@/lib/client/labels";
import { describe, moneyMoved, type Direction } from "@/lib/client/transactions";
import type { Payment } from "@/lib/client/types";
import { Icon, type IconName } from "../icons";
import { StatusIndicator } from "../ui";

const DIRECTION_ICON: Record<Direction, IconName> = { in: "arrow-down-left", out: "arrow-up-right", internal: "swap" };
const DIRECTION_WORD: Record<Direction, string> = { in: "Received", out: "Sent", internal: "Moved between your accounts" };

/** The signed amount a user reads. Failed, rejected and held payments move no money, so they are not coloured as if they did. */
export function SignedAmount({ payment, direction }: { payment: Payment; direction: Direction }) {
  const text = formatMoney(payment.amount, payment.currency);
  const moved = moneyMoved(payment) || payment.status === "REVERSED";
  const sign = direction === "in" ? "+" : direction === "out" ? "\u2212" : "";
  const tone = !moved || payment.status === "REVERSED" ? "inactive" : direction === "in" ? "in" : direction === "out" ? "out" : "internal";
  return (
    <span className={`txn-amount num amount-${tone}`} aria-label={`${DIRECTION_WORD[direction]} ${text}${moved ? "" : ", no money moved"}`}>
      <span aria-hidden="true">{sign}{text}</span>
    </span>
  );
}

export function TransactionRow({ payment, mine, showKind = false }: { payment: Payment; mine: ReadonlySet<string>; showKind?: boolean }) {
  const d = describe(payment, mine);
  const pending = payment.status === "PENDING_REVIEW" || payment.status === "SUBMITTED";
  return (
    <li>
      <Link href={`/payments/${payment.id}`} className="txn-row">
        <span className={`txn-icon txn-icon-${d.direction}${pending ? " txn-icon-pending" : ""}`}>
          <Icon name={pending ? "clock" : DIRECTION_ICON[d.direction]} size={18} />
        </span>
        <span className="txn-main">
          <strong className="txn-title">{d.title}</strong>
          <span className="txn-sub">{d.subtitle} · {formatDateTime(payment.createdAt)}{showKind ? ` · ${kindLabel(payment.kind)}` : ""}</span>
        </span>
        <span className="txn-end">
          <SignedAmount payment={payment} direction={d.direction} />
          <StatusIndicator status={payment.status} />
        </span>
      </Link>
    </li>
  );
}

export function TransactionList({ payments, mine, showKind }: { payments: Payment[]; mine: ReadonlySet<string>; showKind?: boolean }) {
  return (
    <ul className="txn-list" aria-label="Transactions">
      {payments.map((p) => <TransactionRow key={p.id} payment={p} mine={mine} showKind={showKind} />)}
    </ul>
  );
}
