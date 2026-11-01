"use client";

import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, setSessionEndedHandler } from "@/lib/client/api";

interface AuthValue {
  status: "loading" | "authenticated" | "anonymous" | "unavailable";
  userId: string | null;
  isAdmin: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [status, setStatus] = useState<AuthValue["status"]>("loading");
  const [userId, setUserId] = useState<string | null>(null);
  const [roles, setRoles] = useState<string[]>([]);

  const apply = useCallback((s: { authenticated: boolean; userId: string | null; roles: string[] }) => {
    setStatus(s.authenticated ? "authenticated" : "anonymous");
    setUserId(s.userId);
    setRoles(s.roles);
  }, []);

  // Ask the BFF who we are. It renews an expired access token from the refresh cookie when it can.
  useEffect(() => {
    const controller = new AbortController();
    api.session(controller.signal).then(apply).catch((e: unknown) => {
      if (controller.signal.aborted) return;
      const unavailable = (e as { status?: number }).status === 503 || (e as { status?: number }).status === 0;
      setStatus(unavailable ? "unavailable" : "anonymous");
    });
    return () => controller.abort();
  }, [apply]);

  // Any API call that finds the session over sends the user to sign in, remembering where they were.
  useEffect(() => {
    setSessionEndedHandler(() => {
      setStatus("anonymous");
      setUserId(null);
      setRoles([]);
    });
    return () => setSessionEndedHandler(null);
  }, []);

  const isPublic = pathname === "/login" || pathname === "/register";
  useEffect(() => {
    if (status === "anonymous" && !isPublic) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [status, isPublic, pathname, router]);

  const signIn = useCallback(async (email: string, password: string) => apply(await api.login(email, password)), [apply]);
  const signUp = useCallback(async (email: string, password: string) => apply(await api.register(email, password)), [apply]);
  const signOut = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setStatus("anonymous");
      setUserId(null);
      setRoles([]);
      router.replace("/login");
    }
  }, [router]);

  const value = useMemo<AuthValue>(
    () => ({ status, userId, isAdmin: roles.includes("ADMIN"), signIn, signUp, signOut }),
    [status, userId, roles, signIn, signUp, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
