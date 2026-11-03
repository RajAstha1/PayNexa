"use client";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "./auth-provider";
import { NotificationsProvider } from "./shell/notifications-context";
import { SearchCommand } from "./shell/search-command";
import { BottomNav, MobileDrawer, Sidebar, SupportDialog } from "./shell/sidebar";
import { TopHeader } from "./shell/top-header";
import { PayNexaLogo } from "./brand/logo";
import { Button, Spinner } from "./ui";

/** Full-page states (checking the session, redirecting, service down) share the sign-in screens' quiet look. */
function StatusScreen({ children }: { children: ReactNode }) {
  return (
    <main id="main" className="status-screen">
      <PayNexaLogo size={26} />
      {children}
    </main>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const [searchOpen, setSearchOpen] = useState(false);
  const [supportOpen, setSupportOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
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
    return <StatusScreen><p className="status-line"><Spinner label="Checking your session" /> Checking your session…</p></StatusScreen>;
  }
  if (auth.status === "unavailable") {
    return (
      <StatusScreen>
        <div className="status-copy">
          <h1>Service unavailable.</h1>
          <p className="muted">We can&apos;t reach the sign-in service right now. Please try again in a moment.</p>
          <div><Button onClick={() => window.location.reload()}>Try again</Button></div>
        </div>
      </StatusScreen>
    );
  }
  if (!authenticated) {
    return <StatusScreen><p className="status-line"><Spinner label="Redirecting to sign in" /> Redirecting to sign in…</p></StatusScreen>;
  }
  return (
    <NotificationsProvider>
      <div className="app">
        <Sidebar open={navOpen} onClose={() => setNavOpen(false)} onSupport={() => setSupportOpen(true)} />
        <div className="app-main">
          <TopHeader onSearch={() => { setNavOpen(false); setSearchOpen(true); }} onSupport={() => setSupportOpen(true)} onNav={() => setNavOpen(true)} navOpen={navOpen} />
          <main id="main" className="page" tabIndex={-1}>{children}</main>
          <footer className="app-foot">
            <p>Sandbox environment · Synthetic funds only. No real banks or payment rails are connected.</p>
          </footer>
        </div>
        <BottomNav onMenu={() => setMenuOpen(true)} />
      </div>
      <SearchCommand open={searchOpen} onClose={() => setSearchOpen(false)} />
      <SupportDialog open={supportOpen} onClose={() => setSupportOpen(false)} />
      <MobileDrawer open={menuOpen} onClose={() => setMenuOpen(false)} onSupport={() => setSupportOpen(true)} />
    </NotificationsProvider>
  );
}
