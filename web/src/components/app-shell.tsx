"use client";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "./auth-provider";
import { NotificationsProvider } from "./shell/notifications-context";
import { SearchCommand } from "./shell/search-command";
import { BottomNav, MobileDrawer, Sidebar, SupportDialog } from "./shell/sidebar";
import { TopHeader } from "./shell/top-header";
import { Button, Loading } from "./ui";

export function SandboxBanner() {
  return (
    <div className="sandbox-banner" role="note">
      <span className="sandbox-dot" aria-hidden="true" /> <strong>Sandbox.</strong> Synthetic money only. No real funds, banks or payment rails are connected.
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const [searchOpen, setSearchOpen] = useState(false);
  const [supportOpen, setSupportOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const authenticated = auth.status === "authenticated";

  // Cmd/Ctrl+K opens search from anywhere in the console.
  useEffect(() => {
    if (!authenticated) return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearchOpen((o) => !o);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [authenticated]);

  if (auth.status === "loading") {
    return <div className="center-screen"><Loading label="Checking your session" /></div>;
  }
  if (auth.status === "unavailable") {
    return (
      <div className="center-screen">
        <div className="card narrow stack-sm">
          <h1>Service unavailable</h1>
          <p className="muted">We can&apos;t reach the sign-in service right now. Please try again in a moment.</p>
          <Button onClick={() => window.location.reload()}>Try again</Button>
        </div>
      </div>
    );
  }
  if (!authenticated) {
    return <div className="center-screen"><Loading label="Redirecting to sign in" /></div>;
  }
  return (
    <NotificationsProvider>
      <div className="app">
        <Sidebar onSupport={() => setSupportOpen(true)} />
        <div className="app-main">
          <TopHeader onSearch={() => setSearchOpen(true)} onSupport={() => setSupportOpen(true)} />
          <main id="main" className="page" tabIndex={-1}>{children}</main>
        </div>
        <BottomNav onMenu={() => setMenuOpen(true)} />
      </div>
      <SearchCommand open={searchOpen} onClose={() => setSearchOpen(false)} />
      <SupportDialog open={supportOpen} onClose={() => setSupportOpen(false)} />
      <MobileDrawer open={menuOpen} onClose={() => setMenuOpen(false)} onSupport={() => setSupportOpen(true)} />
    </NotificationsProvider>
  );
}
