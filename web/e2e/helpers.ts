import { expect, request, type APIRequestContext, type Browser, type Page } from "@playwright/test";

export const PASSWORD = "correct horse battery staple";
export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? "admin@paynexa.example";
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "";
export const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3100";
export const SHOTS = "e2e/screenshots";
export const uid = () => Math.random().toString(36).slice(2, 8);

export async function signUp(page: Page, email: string) {
  await page.goto("/register");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

export async function openAccount(page: Page): Promise<string> {
  await page.goto("/accounts");
  await page.getByRole("button", { name: "Open account" }).click();
  await expect(page.getByText("Your USD account is ready.")).toBeVisible();
  await page.getByRole("link", { name: /^USD account/ }).first().click();
  await expect(page).toHaveURL(/\/accounts\/[0-9a-f-]{36}/);
  return page.url().split("/").pop()!;
}

export async function newContextPage(browser: Browser) {
  const context = await browser.newContext();
  return { context, page: await context.newPage() };
}

/** Signs out through the profile menu (the only place the button lives on every screen size). */
export async function signOut(page: Page) {
  await page.locator(".profile-trigger").click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

// ---------------------------------------------------------------------------------------------------------
// API seeding. Drives the same BFF the browser uses (same-origin header, cookies), so the data is real.
// ---------------------------------------------------------------------------------------------------------
export interface Api {
  ctx: APIRequestContext;
  post: <T>(path: string, body?: unknown, key?: string) => Promise<T>;
  get: <T>(path: string) => Promise<T>;
}

export async function apiLogin(email: string, password: string, register: boolean): Promise<Api> {
  const ctx = await request.newContext({ baseURL: BASE, extraHTTPHeaders: { Origin: BASE } });
  const res = await ctx.post(register ? "/api/auth/register" : "/api/auth/login", { data: { email, password } });
  expect(res.ok(), `${register ? "register" : "login"} ${email}: ${res.status()}`).toBe(true);
  return {
    ctx,
    async post<T>(path: string, body?: unknown, key?: string) {
      const r = await ctx.post(`/api/backend/${path}`, { data: body, headers: key ? { "Idempotency-Key": key } : {} });
      expect(r.ok(), `POST ${path}: ${r.status()} ${await r.text()}`).toBe(true);
      return (await r.json()) as T;
    },
    async get<T>(path: string) {
      const r = await ctx.get(`/api/backend/${path}`);
      expect(r.ok(), `GET ${path}: ${r.status()}`).toBe(true);
      return (await r.json()) as T;
    },
  };
}

interface Acct { id: string; currency: string }

/**
 * Creates a user with funded USD and EUR accounts, a counterparty, and a handful of real payments between them
 * (including a payout), so every screen has genuine data to render.
 */
export async function seedRichUser() {
  const email = `maya.lindqvist-${uid()}@example.com`;
  const bobEmail = `bob-${uid()}@example.com`;
  const user = await apiLogin(email, PASSWORD, true);
  const bob = await apiLogin(bobEmail, PASSWORD, true);
  const admin = await apiLogin(ADMIN_EMAIL, ADMIN_PASSWORD, false);
  const usd = await user.post<Acct>("payments/accounts", { currency: "USD" });
  const eur = await user.post<Acct>("payments/accounts", { currency: "EUR" });
  const bobUsd = await bob.post<Acct>("payments/accounts", { currency: "USD" });
  await admin.post(`payments/admin/accounts/${usd.id}/fund`, { amount: "2500.00", currency: "USD" }, `seed-${uid()}-usd`);
  await admin.post(`payments/admin/accounts/${eur.id}/fund`, { amount: "800.00", currency: "EUR" }, `seed-${uid()}-eur`);
  await admin.post(`payments/admin/accounts/${bobUsd.id}/fund`, { amount: "300.00", currency: "USD" }, `seed-${uid()}-bob`);
  const transfer = (api: Api, from: string, to: string, amount: string, reference: string) =>
    api.post("payments/payments/transfers", { fromAccountId: from, toAccountId: to, amount, currency: "USD", reference }, `seed-${uid()}-${uid()}`);
  await transfer(user, usd.id, bobUsd.id, "120.00", "Rent share");
  await transfer(user, usd.id, bobUsd.id, "42.75", "Dinner");
  await transfer(bob, bobUsd.id, usd.id, "60.00", "Concert tickets");
  await user.post("payments/payments/payouts", { fromAccountId: usd.id, destination: "bank:demo-ok", amount: "75.00", currency: "USD", reference: "Savings" }, `seed-${uid()}-payout`);
  // Bob tries to send more than he has: a genuine FAILED payment (insufficient funds) that also appears in the user's list.
  await transfer(bob, bobUsd.id, usd.id, "900.00", "Too much");
  return { email, user, bob, admin, usd, eur, bobUsd, bobEmail };
}
