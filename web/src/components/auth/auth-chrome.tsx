"use client";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { PayNexaLogo } from "../brand/logo";
import { safeNext } from "@/lib/client/safe-next";

/** "New to PayNexa? Create account" / "Already registered? Sign in", keeping the page the visitor was heading to. */
function AuthPrompt() {
  const pathname = usePathname();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const suffix = next === "/dashboard" ? "" : `?next=${encodeURIComponent(next)}`;
  const register = pathname === "/register";
  return (
    <p className="auth-prompt">
      <span className="auth-prompt-text">{register ? "Already registered?" : "New to PayNexa?"}</span>
      <Link href={`${register ? "/login" : "/register"}${suffix}`}>{register ? "Sign in" : "Create account"}</Link>
    </p>
  );
}

/**
 * The frame shared by every sign-in screen: a slim header, the page content, and one quiet line about the sandbox.
 * It lives in the (auth) layout, so it stays put while the content underneath changes between pages.
 */
export function AuthChrome({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <header className="auth-header">
        <div className="auth-container auth-header-row">
          <Link href="/login" className="auth-home" aria-label="PayNexa, go to sign in">
            <PayNexaLogo size={26} />
          </Link>
          <Suspense fallback={null}>
            <AuthPrompt />
          </Suspense>
        </div>
      </header>
      <main id="main" className="auth-container auth-main" tabIndex={-1}>
        {children}
      </main>
      <footer className="auth-footer">
        <div className="auth-container">
          <p className="auth-secure">
            Secure sandbox environment · Synthetic funds only
          </p>
        </div>
      </footer>
    </div>
  );
}
