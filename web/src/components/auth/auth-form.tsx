"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "@/lib/client/api";
import { errorText } from "@/lib/client/labels";
import { safeNext } from "@/lib/client/safe-next";
import { useAuth } from "../auth-provider";
import { Icon } from "../icons";
import { Alert, Button, Field } from "../ui";
import { LedgerCaption, LedgerSheet } from "./ledger-sheet";
import { PasswordField } from "./password-field";

export interface CredentialErrors {
  email?: string;
  password?: string;
}

/** Fast, local feedback only. identity-service re-validates everything and is the authority. */
export function validateCredentials(email: string, password: string, register: boolean): CredentialErrors {
  const errors: CredentialErrors = {};
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email.trim())) errors.email = "Enter a valid email address";
  if (register && password.length < 12) errors.password = "Use at least 12 characters";
  else if (!password) errors.password = "Enter your password";
  return errors;
}

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const auth = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const isRegister = mode === "register";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [touched, setTouched] = useState({ email: false, password: false });
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rehide, setRehide] = useState(0);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const errors = validateCredentials(email, password, isRegister);
  const visible: CredentialErrors = {
    email: touched.email || submitted ? errors.email : undefined,
    password: touched.password || submitted ? errors.password : undefined,
  };

  useEffect(() => {
    if (auth.status === "authenticated") router.replace(next);
  }, [auth.status, next, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || done) return;
    setSubmitted(true);
    setError(null);
    if (errors.email || errors.password) {
      (errors.email ? emailRef : passwordRef).current?.focus();
      return;
    }
    setBusy(true);
    try {
      await (isRegister ? auth.signUp(email.trim(), password) : auth.signIn(email.trim(), password));
      setDone(true);
      router.replace(next);
    } catch (err) {
      const api = err instanceof ApiError ? err : null;
      setError(api ? errorText(api.code, api.message) : "Something went wrong. Please try again.");
      // Never keep a rejected password in the form; start the password over without scolding about an empty field.
      setPassword("");
      setSubmitted(false);
      setTouched((t) => ({ ...t, password: false }));
      setRehide((n) => n + 1);
      passwordRef.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  const other = isRegister ? "/login" : "/register";
  const otherHref = `${other}${next === "/dashboard" ? "" : `?next=${encodeURIComponent(next)}`}`;

  return (
    <>
      <section className="auth-panel" aria-labelledby="auth-title">
        <h1 id="auth-title">{isRegister ? "Create your account." : "Welcome back."}</h1>
        <p className="auth-lede">{isRegister ? "Open a PayNexa sandbox account." : "Sign in to continue to your PayNexa account."}</p>

        <form className="auth-form" onSubmit={submit} noValidate>
          <Field label="Email" htmlFor="email" error={visible.email}>
            <input
              id="email"
              ref={emailRef}
              type="email"
              autoComplete="email"
              inputMode="email"
              autoCapitalize="none"
              spellCheck={false}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              onBlur={() => email && setTouched((t) => ({ ...t, email: true }))}
              aria-invalid={!!visible.email}
              aria-describedby={visible.email ? "email-error" : undefined}
              required
            />
          </Field>
          <PasswordField
            id="password"
            label="Password"
            value={password}
            onChange={setPassword}
            onBlur={() => password && setTouched((t) => ({ ...t, password: true }))}
            error={visible.password}
            hint={isRegister ? "At least 12 characters. A passphrase works well." : undefined}
            minLength={isRegister ? 12 : undefined}
            autoComplete={isRegister ? "new-password" : "current-password"}
            inputRef={passwordRef}
            hidden={rehide}
          />

          {error && <Alert tone="bad">{error}</Alert>}

          <div className="auth-actions">
            <Button type="submit" busy={busy} disabled={done} className="btn-auth">
              {done ? <><Icon name="check" size={16} /> {isRegister ? "Account created" : "Signed in"}</>
                : busy ? (isRegister ? "Creating account…" : "Signing in…")
                : (isRegister ? "Create account" : "Sign in")}
            </Button>
          </div>
        </form>

        <p className="auth-switch">
          {isRegister ? "Already have an account? " : "New to PayNexa? "}
          <Link href={otherHref}>{isRegister ? "Sign in" : "Create an account"}</Link>
        </p>
      </section>

      <aside className="auth-aside" aria-label={isRegister ? "How accounts start" : "How payments are recorded"}>
        <LedgerSheet variant={isRegister ? "opening" : "transfer"} />
        <LedgerCaption variant={isRegister ? "opening" : "transfer"} />
      </aside>
    </>
  );
}
