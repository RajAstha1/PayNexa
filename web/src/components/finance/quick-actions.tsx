import Link from "next/link";
import { Icon, type IconName } from "../icons";

export function QuickActionTile({ href, icon, title, text }: { href: string; icon: IconName; title: string; text: string }) {
  return (
    <Link href={href} className="quick-tile">
      <span className="quick-icon"><Icon name={icon} size={20} /></span>
      <span className="quick-text"><strong>{title}</strong><span className="muted small">{text}</span></span>
      <Icon name="chevron-right" size={16} className="quick-chevron" />
    </Link>
  );
}

/** Only things this console can actually do. Sandbox funding is an administrator tool, so only administrators see it. */
export function QuickActions({ isAdmin, hasAccounts }: { isAdmin: boolean; hasAccounts: boolean }) {
  return (
    <div className="quick-grid">
      {hasAccounts && <QuickActionTile href="/send" icon="send" title="Send money" text="Transfer or payout" />}
      <QuickActionTile href="/accounts" icon="plus" title={hasAccounts ? "Open account" : "Open your first account"} text="One per currency" />
      <QuickActionTile href="/payments" icon="payments" title="Payments" text="Search and filter" />
      <QuickActionTile href="/analytics" icon="chart" title="Analytics" text="Volume and trends" />
      {isAdmin && <QuickActionTile href="/admin?tab=accounts" icon="shield" title="Add funds" text="Sandbox funding" />}
    </div>
  );
}
