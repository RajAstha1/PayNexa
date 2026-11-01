/** Server-side configuration. Read lazily so tests can change the environment. Never imported by client code. */

export type ServiceName = "identity" | "payments" | "notifications";

export function serviceUrl(name: ServiceName): string {
  const defaults: Record<ServiceName, string> = {
    identity: "http://localhost:8081",
    payments: "http://localhost:8082",
    notifications: "http://localhost:8083",
  };
  const fromEnv: Record<ServiceName, string | undefined> = {
    identity: process.env.IDENTITY_URL,
    payments: process.env.PAYMENTS_URL,
    notifications: process.env.NOTIFICATIONS_URL,
  };
  return (fromEnv[name] || defaults[name]).replace(/\/+$/, "");
}

export const COOKIES = {
  /** Short-lived access token. Readable by server components so pages can be gated. */
  access: "pn_at",
  /** Refresh token. Only ever sent to /api/*, never to page requests. */
  refresh: "pn_rt",
  /** The sign-in email, kept only so the console can greet the user. Display data, never used for authorization. */
  email: "pn_email",
} as const;

/** Refresh tokens live 7 days in identity-service; the cookie is just a carrier, the server decides real expiry. */
export const REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

export const UPSTREAM_TIMEOUT_MS = 15_000;
export const MAX_REQUEST_BODY_BYTES = 64 * 1024;
