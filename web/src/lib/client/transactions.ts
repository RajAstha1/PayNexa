import { kindLabel } from "./labels";
import { maskId } from "./format";
import type { Payment } from "./types";

/**
 * Which way money moved for this user. "internal" means both accounts belong to the user, so net worth did not change.
 * The services only return account ids (never other people's names), so a counterparty is shown as a masked id.
 */
export type Direction = "in" | "out" | "internal";

export function directionOf(p: Payment, mine: ReadonlySet<string>): Direction {
  const from = mine.has(p.fromAccountId);
  const to = p.toAccountId !== null && mine.has(p.toAccountId);
  if (from && to) return "internal";
  if (from) return "out";
  return "in";
}

/** Money only moved for these statuses, so only these show a coloured, signed amount. */
const MOVED = new Set(["COMPLETED", "SUBMITTED", "SETTLED"]);
export function moneyMoved(p: Payment): boolean {
  return MOVED.has(p.status);
}

export interface Described {
  direction: Direction;
  title: string;
  subtitle: string;
}

/** A human line for a payment, built only from fields the service actually returned. */
export function describe(p: Payment, mine: ReadonlySet<string>): Described {
  const direction = directionOf(p, mine);
  const ref = p.reference?.trim();
  if (p.kind === "FUNDING") return { direction, title: "Sandbox funding", subtitle: ref && ref.toLowerCase() !== "sandbox funding" ? ref : "Synthetic funds added by an administrator" };
  if (p.kind === "PAYOUT") return { direction, title: p.reference?.trim() || "External payout", subtitle: "Payout · simulated rail" };
  if (direction === "internal") return { direction, title: p.reference?.trim() || "Between your accounts", subtitle: "Transfer · your accounts" };
  const other = direction === "out" ? p.toAccountId : p.fromAccountId;
  const who = other ? `${direction === "out" ? "To" : "From"} ${maskId(other)}` : kindLabel(p.kind);
  return { direction, title: p.reference?.trim() || who, subtitle: p.reference?.trim() ? who : kindLabel(p.kind) };
}
