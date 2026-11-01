"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { displayName, initials } from "@/lib/client/format";
import { PayNexaLogo } from "../brand/logo";
import { useAuth } from "../auth-provider";
import { Icon } from "../icons";
import { Dialog } from "../ui";
import { isActive, visibleNav } from "./nav";
import { useShellNotifications } from "./notifications-context";

/** Fixed navy navigation. Full width on desktop, an icon rail on tablets (hidden on phones, which get the bottom bar). */
export function Sidebar({ onSupport }: { onSupport: () => void }) {
  const auth = useAuth();
  const pathname = usePathname();
  const { unread } = useShellNotifications();
  const items = visibleNav(auth.isAdmin);
  const activeIndex = items.findIndex((i) => isActive(pathname, i.href));
  return (
    <aside className="sidebar" aria-label="Sidebar">
      <Link href="/dashboard" className="sidebar-brand" aria-label="PayNexa home">
        <PayNexaLogo tone="light" />
      </Link>
      <nav aria-label="Main" className="sidebar-nav" style={{ "--active": Math.max(activeIndex, 0) } as React.CSSProperties}>
        <span className="nav-indicator" aria-hidden="true" data-visible={activeIndex >= 0} />
        <ul className="nav-list">
          {items.map((item) => {
            const active = isActive(pathname, item.href);
            return (
              <li key={item.href}>
                <Link href={item.href} className="nav-item" aria-current={active ? "page" : undefined} title={item.label}>
                  <Icon name={item.icon} size={20} />
                  <span className="nav-label">{item.label}</span>
                  {item.href === "/notifications" && unread > 0 && (
                    <span className="nav-count" aria-label={`${unread} unread`}>{unread > 99 ? "99+" : unread}</span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="sidebar-foot">
        <button type="button" className="nav-item nav-item-quiet" onClick={onSupport} title="Support">
          <Icon name="help" size={20} />
          <span className="nav-label">Support</span>
        </button>
        <div className="sandbox-chip" title="Sandbox: synthetic money only">
          <span className="sandbox-dot" aria-hidden="true" />
          <span className="nav-label">
            <strong>Sandbox</strong>
            <span>Synthetic money only</span>
          </span>
        </div>
        <div className="profile-chip">
          <span className="avatar" aria-hidden="true">{initials(auth.email)}</span>
          <span className="nav-label profile-text">
            <strong>{displayName(auth.email)}</strong>
            <span>{auth.isAdmin ? "Administrator" : "Customer"}</span>
          </span>
        </div>
      </div>
    </aside>
  );
}

/** Phones: four destinations and a menu, always within thumb reach. */
export function BottomNav({ onMenu }: { onMenu: () => void }) {
  const pathname = usePathname();
  const main = [
    { href: "/dashboard", label: "Home", icon: "dashboard" as const },
    { href: "/accounts", label: "Accounts", icon: "accounts" as const },
    { href: "/send", label: "Send", icon: "send" as const },
    { href: "/payments", label: "Payments", icon: "payments" as const },
  ];
  const { unread } = useShellNotifications();
  const moreActive = ["/notifications", "/analytics", "/admin"].some((h) => isActive(pathname, h));
  return (
    <nav className="bottom-nav" aria-label="Main">
      {main.map((item) => (
        <Link key={item.href} href={item.href} className="bottom-item" aria-current={isActive(pathname, item.href) ? "page" : undefined}>
          <Icon name={item.icon} size={22} />
          <span>{item.label}</span>
        </Link>
      ))}
      <button type="button" className="bottom-item" onClick={onMenu} aria-current={moreActive ? "page" : undefined} aria-haspopup="dialog">
        <span className="bottom-icon">
          <Icon name="menu" size={22} />
          {unread > 0 && <span className="bottom-dot" aria-label={`${unread} unread notifications`} />}
        </span>
        <span>Menu</span>
      </button>
    </nav>
  );
}

export function MobileDrawer({ open, onClose, onSupport }: { open: boolean; onClose: () => void; onSupport: () => void }) {
  const auth = useAuth();
  const pathname = usePathname();
  const { unread } = useShellNotifications();
  return (
    <Dialog open={open} onClose={onClose} title="Menu" placement="left" size="sm">
      <div className="drawer-profile">
        <span className="avatar avatar-lg" aria-hidden="true">{initials(auth.email)}</span>
        <div>
          <strong>{displayName(auth.email)}</strong>
          <div className="muted small">{auth.email}</div>
        </div>
      </div>
      <ul className="drawer-list">
        {visibleNav(auth.isAdmin).map((item) => (
          <li key={item.href}>
            <Link href={item.href} onClick={onClose} className="drawer-item" aria-current={isActive(pathname, item.href) ? "page" : undefined}>
              <Icon name={item.icon} size={20} /> {item.label}
              {item.href === "/notifications" && unread > 0 && <span className="nav-count">{unread}</span>}
            </Link>
          </li>
        ))}
        <li>
          <button type="button" className="drawer-item" onClick={() => { onClose(); onSupport(); }}><Icon name="help" size={20} /> Support</button>
        </li>
        <li>
          <button type="button" className="drawer-item" onClick={() => void auth.signOut()}><Icon name="logout" size={20} /> Sign out</button>
        </li>
      </ul>
      <div className="sandbox-note"><span className="sandbox-dot" aria-hidden="true" /> Sandbox: synthetic money only</div>
    </Dialog>
  );
}

export function SupportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose} title="Support" description="PayNexa is a sandbox environment." size="sm"
      footer={<button type="button" className="btn btn-primary" onClick={onClose}>Got it</button>}>
      <div className="stack-sm">
        <p>There is no live support team behind this console, and it holds no real money. Everything here is synthetic.</p>
        <ul className="support-list">
          <li><Icon name="info" size={18} /> If something fails, the message shows a <strong>reference</strong>. It is the correlation id that appears in every service&apos;s logs for that request.</li>
          <li><Icon name="clock" size={18} /> Payouts stay <strong>In flight</strong> until the simulated rail confirms them. Notifications arrive a moment after a payment changes.</li>
          <li><Icon name="shield" size={18} /> Payments held for review are released by an administrator, who cannot approve their own.</li>
        </ul>
        <p className="muted small">Design notes and limits are documented in the repository&apos;s <code>docs/</code> folder.</p>
      </div>
    </Dialog>
  );
}
