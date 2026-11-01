"use client";
import Link from "next/link";
import { useRef, useState } from "react";
import { displayName, initials, relativeTime } from "@/lib/client/format";
import { notificationInfo } from "@/lib/client/labels";
import { PayNexaMark } from "../brand/logo";
import { useAuth } from "../auth-provider";
import { Icon } from "../icons";
import { useDismiss } from "../ui";
import { useShellNotifications } from "./notifications-context";

export function TopHeader({ onSearch, onSupport }: { onSearch: () => void; onSupport: () => void }) {
  const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
  return (
    <header className="topbar">
      <Link href="/dashboard" className="topbar-mark" aria-label="PayNexa home"><PayNexaMark size={34} /></Link>
      <button type="button" className="search-trigger" onClick={onSearch} aria-label="Search" aria-haspopup="dialog">
        <Icon name="search" size={18} />
        <span className="search-text">Search accounts, payments, pages</span>
        <kbd className="kbd" suppressHydrationWarning>{isMac ? "\u2318K" : "Ctrl K"}</kbd>
      </button>
      <div className="topbar-end">
        <span className="sandbox-pill" title="Sandbox: synthetic money only"><span className="sandbox-dot" aria-hidden="true" />Sandbox</span>
        <NotificationBell />
        <ProfileMenu onSupport={onSupport} />
      </div>
    </header>
  );
}

function NotificationBell() {
  const { items, unread } = useShellNotifications();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useDismiss(ref, open, () => setOpen(false));
  const recent = items.slice(0, 5);
  return (
    <div className="menu-wrap" ref={ref}>
      <button type="button" className="icon-btn icon-btn-bordered bell" aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
        aria-haspopup="true" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon name="bell" size={20} />
        {unread > 0 && <span className="bell-badge" aria-hidden="true">{unread > 9 ? "9+" : unread}</span>}
      </button>
      {open && (
        <div className="popover popover-wide" role="region" aria-label="Recent notifications">
          <div className="popover-head">
            <strong>Notifications</strong>
            <Link href="/notifications" onClick={() => setOpen(false)} className="small">View all</Link>
          </div>
          {recent.length === 0 ? (
            <p className="popover-empty muted">You&apos;re all caught up.</p>
          ) : (
            <ul>
              {recent.map((n) => (
                <li key={n.id}>
                  <Link href={`/payments/${n.paymentId}`} className="popover-item" onClick={() => setOpen(false)}>
                    <span className={`unread-dot${n.read ? " read" : ""}`} aria-label={n.read ? undefined : "Unread"} />
                    <span className="popover-item-body">
                      <strong>{notificationInfo(n.kind).title}</strong>
                      <span className="muted small clamp-2">{n.message}</span>
                      <span className="faint small">{relativeTime(n.createdAt)}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

function ProfileMenu({ onSupport }: { onSupport: () => void }) {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useDismiss(ref, open, () => {
    setOpen(false);
    trigger.current?.focus();
  });
  const close = () => setOpen(false);
  return (
    <div className="menu-wrap" ref={ref}>
      <button ref={trigger} type="button" className="profile-trigger" aria-haspopup="true" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="avatar" aria-hidden="true">{initials(auth.email)}</span>
        <span className="profile-trigger-text">
          <strong>{displayName(auth.email)}</strong>
          <span>{auth.isAdmin ? "Administrator" : "Customer"}</span>
        </span>
        <Icon name="chevron-down" size={16} className="profile-caret" />
      </button>
      {open && (
        <div className="popover" role="menu" aria-label="Account menu">
          <div className="popover-profile">
            <strong>{displayName(auth.email)}</strong>
            <span className="muted small">{auth.email ?? "Signed in"}</span>
          </div>
          <Link role="menuitem" href="/accounts" className="menu-item" onClick={close}><Icon name="accounts" size={18} /> Accounts</Link>
          <Link role="menuitem" href="/notifications" className="menu-item" onClick={close}><Icon name="bell" size={18} /> Notifications</Link>
          <button role="menuitem" type="button" className="menu-item" onClick={() => { close(); onSupport(); }}><Icon name="help" size={18} /> Support</button>
          <div className="menu-sep" />
          <button role="menuitem" type="button" className="menu-item" onClick={() => void auth.signOut()}><Icon name="logout" size={18} /> Sign out</button>
        </div>
      )}
    </div>
  );
}
