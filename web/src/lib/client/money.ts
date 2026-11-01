import { CURRENCIES } from "./types";

const FRACTION_DIGITS: Record<string, number> = { USD: 2, EUR: 2, GBP: 2, JPY: 0, INR: 2, CAD: 2, AUD: 2, CHF: 2 };

export function fractionDigits(currency: string): number {
  return FRACTION_DIGITS[currency] ?? 2;
}

export function isSupportedCurrency(currency: string): boolean {
  return (CURRENCIES as readonly string[]).includes(currency);
}

export type AmountCheck = { ok: true; normalized: string } | { ok: false; message: string };

const MAX_MAJOR = 10_000_000_000; // matches the backend's 10^12 minor-unit cap for 2-decimal currencies

/**
 * Client-side validation for fast feedback only. The backend re-validates everything and is the authority.
 * Accepts a plain positive decimal with no more fraction digits than the currency allows.
 */
export function checkAmount(input: string, currency: string): AmountCheck {
  const value = input.trim();
  if (!value) return { ok: false, message: "Enter an amount" };
  if (!/^\d+(\.\d+)?$/.test(value)) return { ok: false, message: "Use digits and a decimal point only, for example 12.50" };
  const digits = fractionDigits(currency);
  const fraction = value.split(".")[1] ?? "";
  if (fraction.length > digits) {
    return { ok: false, message: digits === 0 ? `${currency} has no decimal places` : `${currency} allows at most ${digits} decimal places` };
  }
  if (/^0*(\.0*)?$/.test(value)) return { ok: false, message: "Amount must be greater than zero" };
  if (Number(value) > MAX_MAJOR) return { ok: false, message: "Amount is too large" };
  return { ok: true, normalized: value };
}

/** Formats a decimal-string amount for display. Intl accepts decimal strings, so no binary floating point is involved. */
export function formatMoney(amount: string, currency: string): string {
  try {
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency,
      minimumFractionDigits: fractionDigits(currency),
      maximumFractionDigits: fractionDigits(currency),
    }).format(amount as unknown as number);
  } catch {
    return `${amount} ${currency}`;
  }
}

/** Exact conversion to integer minor units (cents). Input must already pass {@link checkAmount}. */
export function toMinorUnits(amount: string, currency: string): bigint {
  const digits = fractionDigits(currency);
  const [whole = "0", fraction = ""] = amount.trim().split(".");
  return BigInt(whole + fraction.padEnd(digits, "0").slice(0, digits));
}

/** True when `amount` is strictly greater than `balance`. Exact, no floating point. */
export function exceeds(amount: string, balance: string, currency: string): boolean {
  return toMinorUnits(amount, currency) > toMinorUnits(balance, currency);
}
