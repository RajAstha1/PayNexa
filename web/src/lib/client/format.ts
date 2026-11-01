/** Small presentation helpers. Pure functions, no network, easy to test. */

const COUNTRY_BY_CURRENCY: Record<string, string> = {
  USD: "US", EUR: "EU", GBP: "GB", JPY: "JP", INR: "IN", CAD: "CA", AUD: "AU", CHF: "CH",
};
const CURRENCY_NAMES: Record<string, string> = {
  USD: "US dollar", EUR: "Euro", GBP: "British pound", JPY: "Japanese yen",
  INR: "Indian rupee", CAD: "Canadian dollar", AUD: "Australian dollar", CHF: "Swiss franc",
};

/** Region code shown beside a currency (a text badge, since flag emoji do not render on every platform). */
export function countryFor(currency: string): string {
  return COUNTRY_BY_CURRENCY[currency] ?? currency.slice(0, 2);
}
export function currencyName(currency: string): string {
  return CURRENCY_NAMES[currency] ?? currency;
}

/** The services have no profile name, so the name shown is the email's local part, tidied up. */
export function displayName(email: string | null | undefined): string {
  const local = (email ?? "").split("@")[0] ?? "";
  const words = local.split(/[._+-]+/).filter((w) => w.length > 0);
  if (words.length === 0) return "there";
  return words.slice(0, 2).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}
export function firstName(email: string | null | undefined): string {
  return displayName(email).split(" ")[0] ?? "there";
}
export function initials(email: string | null | undefined): string {
  const name = displayName(email);
  if (name === "there") return "?";
  return name.split(" ").map((w) => w.charAt(0)).join("").slice(0, 2).toUpperCase();
}

export function greeting(now: Date = new Date()): string {
  const h = now.getHours();
  return h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

/** Account ids are internal UUIDs. Show only the tail; the full value stays available to copy. */
export function maskId(id: string): string {
  return `\u2022\u2022\u2022\u2022 ${id.replace(/-/g, "").slice(-4)}`;
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}
export function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

/** "just now", "5 min ago", "3 h ago", otherwise the date. */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const seconds = Math.round((now - t) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} h ago`;
  if (seconds < 7 * 86_400) return `${Math.round(seconds / 86_400)} d ago`;
  return formatDate(iso);
}

/** YYYY-MM-DD for a local calendar date, as the payments filters expect. */
export function isoDay(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** "$", "€", "£", "¥", "₹", "CA$", "A$", "CHF": the symbol Intl uses for this currency in en-US. */
export function currencySymbol(currency: string): string {
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency, currencyDisplay: "narrowSymbol" }).formatToParts(0).find((p) => p.type === "currency")?.value ?? currency;
  } catch {
    return currency;
  }
}
