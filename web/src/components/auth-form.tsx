"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { ApiError } from "@/lib/client/api";
import { errorText } from "@/lib/client/labels";
import { useAuth } from "./auth-provider";
import { SandboxBanner } from "./app-shell";
import { PayNexaLogo } from "./brand/logo";
import { Icon } from "./icons";
import { Alert, Button, Field } from "./ui";

/** Only same-site relative paths are honoured, so a crafted ?next= can never bounce a user to another site. */
export function safeNext(candidate: string | null): string {
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\") || /[\u0000-\u001f]/.test(candidate)) {
    return "/dashboard";
  }
  return candidate;
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const auth = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});

  const isRegister = mode === "register";

  useEffect(() => {
    if (auth.status === "authenticated") router.replace(next);
  }, [auth.status, next, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const errs: typeof fieldErrors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email.trim())) errs.email = "Enter a valid email address";
    if (isRegister && password.length < 12) errs.password = "Use at least 12 characters";
    if (!password) errs.password = errs.password ?? "Enter your password";
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setBusy(true);
    setError(null);
    try {
      await (isRegister ? auth.signUp(email.trim(), password) : auth.signIn(email.trim(), password));
      router.replace(next);
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      setError(api ? errorText(api.code, api.message) : "Something went wrong. Please try again.");
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-shell">
      <SandboxBanner />
      <div className="auth-split">
        <aside className="auth-aside" aria-hidden="false">
          <PayNexaLogo tone="light" size={40} />
          <div className="auth-pitch">
            <h2>Payments you can follow from start to finish.</h2>
            <ul>
              <li><Icon name="check" size={18} /> Every movement is recorded twice, so balances always add up.</li>
              <li><Icon name="check" size={18} /> A retried payment is never applied twice.</li>
              <li><Icon name="check" size={18} /> Large payments wait for a second person to approve them.</li>
            </ul>
          </div>
          <p className="auth-fine"><span className="sandbox-dot" aria-hidden="true" /> Sandbox: synthetic money only. No real funds or banks are connected.</p>
        </aside>
        <main id="main" className="auth-main">
          <div className="auth-card">
            <div className="auth-mobile-logo"><PayNexaLogo tone="dark" size={36} /></div>
            <h1>{isRegister ? "Create your account" : "Welcome back"}</h1>
            <p className="muted">{isRegister ? "Open a PayNexa sandbox account." : "Sign in to your PayNexa console."}</p>
            {error && <Alert tone="bad">{error}</Alert>}
            <form className="form" onSubmit={submit} noValidate>
              <Field label="Email" htmlFor="email" error={fieldErrors.email}>
                <input id="email" type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)}
                  aria-invalid={!!fieldErrors.email} aria-describedby={fieldErrors.email ? "email-error" : undefined} required />
              </Field>
              <Field label="Password" htmlFor="password" error={fieldErrors.password}
                hint={isRegister ? "At least 12 characters. A passphrase works well." : undefined}>
                <input id="password" type="password" autoComplete={isRegister ? "new-password" : "current-password"} value={password}
                  onChange={(e) => setPassword(e.target.value)} aria-invalid={!!fieldErrors.password}
                  aria-describedby={fieldErrors.password ? "password-error" : isRegister ? "password-hint" : undefined} required />
              </Field>
              <Button type="submit" busy={busy} className="btn-block">{isRegister ? "Create account" : "Sign in"}</Button>
            </form>
            <p className="muted small auth-switch">
              {isRegister ? (
                <>Already have an account? <Link href={`/login?next=${encodeURIComponent(next)}`}>Sign in</Link></>
              ) : (
                <>New here? <Link href={`/register?next=${encodeURIComponent(next)}`}>Create an account</Link></>
              )}
            </p>
          </div>
        </main>
      </div>
    </div>
  );
}
