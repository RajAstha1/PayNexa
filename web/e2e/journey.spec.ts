import { expect, test } from "@playwright/test";
import { ADMIN_EMAIL, ADMIN_PASSWORD, PASSWORD, SHOTS, newContextPage, openAccount, signIn, signOut, signUp, uid } from "./helpers";

test.describe("anonymous visitors", () => {
  test("are sent to sign in and returned to where they were going", async ({ page }) => {
    await page.goto("/payments");
    await expect(page).toHaveURL(/\/login\?next=%2Fpayments/);
    await expect(page.getByText("Sandbox.")).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/01-login.png` });
  });

  test("get a clear message for wrong credentials and no hint which part was wrong", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("Email").fill(`nobody-${uid()}@example.com`);
    await page.getByLabel("Password").fill("definitely wrong password");
    await page.getByRole("button", { name: "Sign in" }).click();
    // Next.js also renders a hidden role=alert route announcer, so target our own alert by its text.
    await expect(page.getByRole("alert").filter({ hasText: "That email and password do not match." })).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("see field validation before anything is sent", async ({ page }) => {
    await page.goto("/register");
    await page.getByLabel("Email").fill("not-an-email");
    await page.getByLabel("Password").fill("short");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByText("Enter a valid email address")).toBeVisible();
    await expect(page.getByText("Use at least 12 characters")).toBeVisible();
  });

  test("cannot be redirected off-site through the next parameter", async ({ page }) => {
    const email = `redir-${uid()}@example.com`;
    await page.goto("/register?next=https://evil.example/steal");
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page).toHaveURL(/\/dashboard/);
    expect(new URL(page.url()).hostname).not.toBe("evil.example");
  });
});

test.describe("a customer's journey", () => {
  test("registers, opens accounts, pays another user, and both sides see it", async ({ browser }) => {
    test.skip(!ADMIN_PASSWORD, "set E2E_ADMIN_PASSWORD (printed by runLocal) to fund accounts");
    const alice = await newContextPage(browser);
    const bob = await newContextPage(browser);
    const admin = await newContextPage(browser);
    const aliceEmail = `alice-${uid()}@example.com`;
    const bobEmail = `bob-${uid()}@example.com`;

    // --- sign up both and open accounts
    await signUp(alice.page, aliceEmail);
    await expect(alice.page.getByText("No accounts yet")).toBeVisible();
    await alice.page.screenshot({ path: `${SHOTS}/02-dashboard-empty.png` });
    const aliceAccount = await openAccount(alice.page);
    await signUp(bob.page, bobEmail);
    const bobAccount = await openAccount(bob.page);

    // --- the administrator adds synthetic funds
    await signIn(admin.page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await expect(admin.page.getByRole("link", { name: "Admin" })).toBeVisible();
    await admin.page.goto("/admin");
    await admin.page.getByRole("tab", { name: "Accounts" }).click();
    await admin.page.getByLabel("Account ID").fill(aliceAccount);
    await admin.page.getByLabel("Amount").fill("500.00");
    await admin.page.getByRole("button", { name: "Add synthetic funds" }).click();
    await expect(admin.page.getByText(/Added 500.00 USD of synthetic funds/)).toBeVisible();

    // --- Alice sees the money
    await alice.page.goto(`/accounts/${aliceAccount}`);
    await expect(alice.page.getByTestId("account-balance")).toHaveText("$500.00");
    await alice.page.screenshot({ path: `${SHOTS}/03-account.png`, fullPage: true });

    // --- Alice sends 25.50 to Bob: validation, review step, confirmation
    await alice.page.goto("/send");
    await alice.page.getByLabel("Receiving account ID").fill(bobAccount);
    await alice.page.getByLabel(/^Amount/).fill("25.555");
    await alice.page.getByRole("button", { name: "Review payment" }).click();
    await expect(alice.page.getByText("USD allows at most 2 decimal places")).toBeVisible();
    await alice.page.getByLabel(/^Amount/).fill("25.50");
    await alice.page.getByLabel("Reference (optional)").fill("lunch");
    await alice.page.getByRole("button", { name: "Review payment" }).click();
    await expect(alice.page.getByRole("heading", { name: "Review and confirm" })).toBeVisible();
    await alice.page.screenshot({ path: `${SHOTS}/04-send-review.png` });
    await alice.page.getByRole("button", { name: "Confirm and send" }).click();
    await expect(alice.page.getByRole("heading", { name: "Payment sent" })).toBeVisible();
    await alice.page.screenshot({ path: `${SHOTS}/05-send-result.png` });

    // --- balances moved exactly
    await alice.page.goto(`/accounts/${aliceAccount}`);
    await expect(alice.page.getByTestId("account-balance")).toHaveText("$474.50");
    await bob.page.goto(`/accounts/${bobAccount}`);
    await expect(bob.page.getByTestId("account-balance")).toHaveText("$25.50");

    // --- Alice's payment page shows the timeline
    await alice.page.goto("/payments");
    await alice.page.locator("a.table-link").first().click();
    await expect(alice.page.getByRole("list", { name: "Status history" })).toContainText("Completed");
    await alice.page.screenshot({ path: `${SHOTS}/06-payment-detail.png`, fullPage: true });

    // --- Bob is notified (delivered through Kafka, so allow a moment)
    await bob.page.goto("/notifications");
    await expect(bob.page.getByText("You received 25.50 USD.")).toBeVisible({ timeout: 30_000 });
    await bob.page.screenshot({ path: `${SHOTS}/07-notifications.png` });

    // --- Bob cannot open Alice's account
    await bob.page.goto(`/accounts/${aliceAccount}`);
    await expect(bob.page.getByText("That account was not found.")).toBeVisible();

    await Promise.all([alice.context.close(), bob.context.close(), admin.context.close()]);
  });

  test("an insufficient-funds payment is reported as an outcome, not a crash", async ({ page }) => {
    await signUp(page, `poor-${uid()}@example.com`);
    const mine = await openAccount(page);
    const other = await newContextPage(page.context().browser()!);
    await signUp(other.page, `rcv-${uid()}@example.com`);
    const theirs = await openAccount(other.page);

    await page.goto(`/send?from=${mine}`);
    await page.getByLabel("Receiving account ID").fill(theirs);
    await page.getByLabel(/^Amount/).fill("10.00");
    await expect(page.getByText(/more than your available balance/)).toBeVisible();
    await page.getByRole("button", { name: "Review payment" }).click();
    await page.getByRole("button", { name: "Confirm and send" }).click();
    await expect(page.getByRole("heading", { name: "Payment not sent" })).toBeVisible();
    await expect(page.getByText("Insufficient funds")).toBeVisible();
    await other.context.close();
  });

  test("a user cannot see the admin area", async ({ page }) => {
    await signUp(page, `plain-${uid()}@example.com`);
    await expect(page.getByRole("link", { name: "Admin" })).toHaveCount(0);
    await page.goto("/admin");
    await expect(page.getByText("Administrators only")).toBeVisible();
  });

  test("signing out ends the session, and the back button does not bring the data back", async ({ page }) => {
    await signUp(page, `out-${uid()}@example.com`);
    await signOut(page);
    await expect(page).toHaveURL(/\/login/);
    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);
    const cookies = await page.context().cookies();
    expect(cookies.filter((c) => c.name.startsWith("pn_") && c.value !== "")).toHaveLength(0);
  });
});

test.describe("tokens never reach page JavaScript", () => {
  test("auth cookies are HttpOnly and SameSite=Strict, and invisible to document.cookie", async ({ page }) => {
    await signUp(page, `cookie-${uid()}@example.com`);
    const cookies = (await page.context().cookies()).filter((c) => c.name.startsWith("pn_"));
    expect(cookies.map((c) => c.name).sort()).toEqual(["pn_at", "pn_email", "pn_rt"]);
    for (const c of cookies) {
      expect(c.httpOnly, c.name).toBe(true);
      expect(c.sameSite, c.name).toBe("Strict");
    }
    expect(await page.evaluate(() => document.cookie)).toBe("");
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toMatch(/eyJ/);
  });
});

test.describe("administration", () => {
  test.skip(!ADMIN_PASSWORD, "set E2E_ADMIN_PASSWORD (printed by runLocal)");

  test("a large payment is held, then released by an administrator; reconciliation is clean", async ({ browser }) => {
    const alice = await newContextPage(browser);
    const bob = await newContextPage(browser);
    const admin = await newContextPage(browser);
    await signUp(alice.page, `big-${uid()}@example.com`);
    const from = await openAccount(alice.page);
    await signUp(bob.page, `bigrcv-${uid()}@example.com`);
    const to = await openAccount(bob.page);

    await signIn(admin.page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await admin.page.goto("/admin");
    await admin.page.getByRole("tab", { name: "Accounts" }).click();
    await admin.page.getByLabel("Account ID").fill(from);
    await admin.page.getByLabel("Amount").fill("9000.00");
    await admin.page.getByRole("button", { name: "Add synthetic funds" }).click();
    await expect(admin.page.getByText(/Added 9000.00 USD/)).toBeVisible();

    await alice.page.goto(`/send?from=${from}`);
    await alice.page.getByLabel("Receiving account ID").fill(to);
    await alice.page.getByLabel(/^Amount/).fill("3000.00");
    await alice.page.getByRole("button", { name: "Review payment" }).click();
    await alice.page.getByRole("button", { name: "Confirm and send" }).click();
    await expect(alice.page.getByRole("heading", { name: "Held for review" })).toBeVisible();
    await alice.page.screenshot({ path: `${SHOTS}/08-held.png` });

    await admin.page.reload();
    await expect(admin.page.getByRole("tab", { name: "Review queue" })).toHaveAttribute("aria-selected", "true");
    await expect(admin.page.getByText("3,000.00").first()).toBeVisible();
    await admin.page.screenshot({ path: `${SHOTS}/09-admin-review.png`, fullPage: true });
    await admin.page.getByRole("button", { name: "Approve" }).first().click();
    await admin.page.getByRole("button", { name: "Approve and move money" }).click();
    await expect(admin.page.getByText(/is now completed/)).toBeVisible();

    await bob.page.goto(`/accounts/${to}`);
    await expect(bob.page.getByTestId("account-balance")).toHaveText("$3,000.00");

    await admin.page.getByRole("tab", { name: "Reconciliation" }).click();
    await admin.page.getByRole("button", { name: "Run reconciliation" }).click();
    await expect(admin.page.getByText("Clean: the books are consistent.")).toBeVisible();
    await admin.page.screenshot({ path: `${SHOTS}/10-admin-reconciliation.png` });
    await Promise.all([alice.context.close(), bob.context.close(), admin.context.close()]);
  });
});
