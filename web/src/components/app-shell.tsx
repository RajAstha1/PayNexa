"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { api } from "@/lib/client/api";
import { useAsync } from "@/lib/client/use-async";
import { useAuth } from "./auth-provider";
import { Button, Loading } from "./ui";

const NAV = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/accounts", label: "Accounts" },
  { href: "/send", label: "Send money" },
  { href: "/payments", label: "Payments" },
  { href: "/notifications", label: "Notifications" },
];

export function SandboxBanner() {
  return (
    <div className="sandbox-banner" role="note">
      <strong>Sandbox.</strong> Synthetic money only. No real funds, banks or payment rails are connected.
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const pathname = usePathname();

  // Unread count in the nav; failures here must never break the page.
  const notifications = useAsync((s) => api.notifications(s), [auth.status], { enabled: auth.status === "authenticated", pollMs: 15000, pollWhile: () => true });
  const unread = notifications.data?.filter((n) => !n.read).length ?? 0;

  if (auth.status === "loading") {
    return (
      <div className="center-screen">
        <Loading label="Checking your session" />
      </div>
    );
  }
  if (auth.status === "unavailable") {
    return (
      <div className="center-screen">
        <div className="card narrow">
          <h1>Service unavailable</h1>
          <p className="muted">We can&apos;t reach the sign-in service right now. Please try again in a moment.</p>
          <Button onClick={() => window.location.reload()}>Try again</Button>
        </div>
      </div>
    );
  }
  if (auth.status !== "authenticated") {
    return (
      <div className="center-screen">
        <Loading label="Redirecting to sign in" />
      </div>
    );
  }

  const items = auth.isAdmin ? [...NAV, { href: "/admin", label: "Admin" }] : NAV;
  return (
    <div className="shell">
      <SandboxBanner />
      <header className="topbar">
        <Link href="/dashboard" className="brand" aria-label="PayNexa home">
          <span className="brand-mark" aria-hidden="true">P</span> PayNexa
        </Link>
        <nav aria-label="Main">
          <ul className="nav">
            {items.map((item) => {
              const active = pathname === item.href || pathname.startsWith(item.href + "/");
              return (
                <li key={item.href}>
                  <Link href={item.href} aria-current={active ? "page" : undefined} className={active ? "nav-link active" : "nav-link"}>
                    {item.label}
                    {item.href === "/notifications" && unread > 0 && (
                      <span className="pill" aria-label={`${unread} unread`}>{unread}</span>
                    )}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
        <div className="topbar-end">
          {auth.isAdmin && <span className="badge badge-info">Admin</span>}
          <Button variant="ghost" onClick={() => void auth.signOut()}>Sign out</Button>
        </div>
      </header>
      <main id="main" className="page">{children}</main>
    </div>
  );
}
