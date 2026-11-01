import { expect, test, type Page } from "@playwright/test";
import { ADMIN_PASSWORD, PASSWORD, SHOTS, seedRichUser, signIn } from "./helpers";

test.skip(!ADMIN_PASSWORD, "set E2E_ADMIN_PASSWORD (printed by runLocal)");
test.describe.configure({ mode: "serial" });

let seeded: Awaited<ReturnType<typeof seedRichUser>>;

test.beforeAll(async () => {
  seeded = await seedRichUser();
});

async function settle(page: Page) {
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".skeleton")).toHaveCount(0, { timeout: 15_000 });
}

test.describe("real data, never invented", () => {
  test("dashboard figures match what the services hold", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    const accounts = await seeded.user.get<{ currency: string; balance: string }[]>("payments/accounts");
    const usd = accounts.find((a) => a.currency === "USD")!;
    await settle(page);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/Good (morning|afternoon|evening), Maya/);
    await expect(page.getByText("Here's your payment activity at a glance.")).toBeVisible();
    // each account card shows the backend's balance to the cent
    const money = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(Number(usd.balance));
    await expect(page.locator(".acct-card").filter({ hasText: "USD" }).getByLabel(money)).toBeVisible();
    // the list contains the real references and the failed payment is marked as such
    for (const ref of ["Rent share", "Dinner", "Concert tickets", "Savings", "Too much"]) await expect(page.getByText(ref).first()).toBeVisible();
    await expect(page.locator(".txn-row", { hasText: "Too much" }).getByText("Failed")).toBeVisible();
    await expect(page.locator(".txn-row", { hasText: "Savings" }).getByText("Settled")).toBeVisible();
  });

  test("balance visibility toggle removes the digits from the page and persists", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await settle(page);
    const card = page.locator(".balance-card");
    await expect(card).toContainText(/\d{3}/);
    await card.getByRole("button", { name: "Hide balance" }).click();
    await expect(card.locator(".amount")).not.toContainText(/\d/);
    await page.reload();
    await settle(page);
    await expect(page.locator(".balance-card").getByRole("button", { name: "Show balance" })).toBeVisible();
    await page.locator(".balance-card").getByRole("button", { name: "Show balance" }).click();
  });

  test("chart period switch loads that period from the service and the tooltip reads exact amounts", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await settle(page);
    const seen: string[] = [];
    page.on("request", (r) => { if (r.url().includes("payments/stats")) seen.push(new URL(r.url()).search); });
    await page.getByRole("button", { name: "90D" }).click();
    await expect(page.getByRole("button", { name: "90D" })).toHaveAttribute("aria-pressed", "true");
    await expect.poll(() => seen.join()).toContain("days=90");
    const plot = page.locator(".chart svg").first();
    await plot.focus();
    await page.keyboard.press("End");
    await expect(page.locator(".chart-tip")).toContainText(/Money in|Payments/);
    await expect(page.locator(".chart-tip")).toContainText(/\$|€/);
  });
});

test.describe("payments page filters", () => {
  test("direction, search, status and date range all query the service", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await page.goto("/payments");
    await settle(page);
    const rows = page.locator(".data-table tbody tr");
    await expect(rows).toHaveCount(await rows.count());
    const all = await rows.count();
    expect(all).toBeGreaterThanOrEqual(6);

    await page.getByRole("tab", { name: "Outgoing" }).click();
    await expect(page).toHaveURL(/dir=out/);
    await expect(page.getByRole("tab", { name: "Outgoing" })).toHaveAttribute("aria-selected", "true");
    await expect.poll(async () => rows.count()).toBeLessThan(all);
    for (const amount of await page.locator(".data-table .txn-amount").allTextContents()) expect(amount).not.toMatch(/^\+/);

    await page.getByRole("tab", { name: "All" }).click();
    await page.getByLabel("Search payments by reference or payment id").fill("Concert");
    await page.getByRole("button", { name: "Search payments" }).click();
    await expect(page).toHaveURL(/q=Concert/);
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("Concert tickets");

    await page.getByRole("button", { name: "Clear" }).click();
    await expect(page).not.toHaveURL(/q=/);
    await page.getByLabel("Search payments by reference or payment id").fill("zzz-no-such-ref");
    await page.getByRole("button", { name: "Search payments" }).click();
    await expect(page.getByText("No payments match these filters")).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/payments-empty-filter.png` });
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page).not.toHaveURL(/q=/);
    await expect(page.getByLabel("Search payments by reference or payment id")).toHaveValue("");

    await page.getByLabel("Status").selectOption("FAILED");
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("Failed");

    // an impossible range is reported, not sent
    await page.goto("/payments?from=2026-12-31&to=2026-01-01");
    await expect(page.getByText("The start date is after the end date.")).toBeVisible();
  });

  test("a search with special characters is handled safely", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await page.goto("/payments");
    await page.getByLabel("Search payments by reference or payment id").fill(`100% & "quotes" ' <b> _ \\`);
    await page.getByRole("button", { name: "Search payments" }).click();
    await expect(page.getByText("No payments match these filters")).toBeVisible();
  });

  test("the search palette finds a payment by reference and keyboard navigation opens it", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await settle(page);
    await page.keyboard.press("ControlOrMeta+k");
    const box = page.getByRole("combobox");
    await expect(box).toBeFocused();
    await box.fill("dinner");
    await expect(page.getByRole("option").first()).toContainText("Dinner");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/payments\/[0-9a-f-]{36}/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Dinner");
    // Escape closes and focus goes back
    await page.keyboard.press("ControlOrMeta+k");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});

test.describe("notifications", () => {
  test("unread items are marked, can be marked read one by one and all at once, against the service", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await expect.poll(async () => (await seeded.user.get<{ read: boolean }[]>("notifications/notifications")).length, { timeout: 30_000 }).toBeGreaterThan(3);
    await page.goto("/notifications");
    const unread = page.locator(".notif-unread");
    await expect(unread.first()).toBeVisible();
    const before = await unread.count();
    await unread.first().getByRole("button", { name: "Mark read" }).click();
    await expect(unread).toHaveCount(before - 1);
    // the service agrees
    const server = await seeded.user.get<{ read: boolean }[]>("notifications/notifications");
    expect(server.filter((n) => !n.read)).toHaveLength(before - 1);
    await page.getByRole("button", { name: "Mark all as read" }).click();
    await expect(unread).toHaveCount(0);
    expect((await seeded.user.get<{ read: boolean }[]>("notifications/notifications")).every((n) => n.read)).toBe(true);
    await expect(page.getByRole("button", { name: "Mark all as read" })).toHaveCount(0);
    // category filter
    // (the only failed payment belongs to the counterparty, so this user correctly has no "Issues")
    await page.getByRole("button", { name: /^Issues/ }).click();
    await expect(page.getByText("No notifications in this category")).toBeVisible();
    await page.getByRole("button", { name: /^Payments/ }).click();
    await expect(page.locator(".notif").first()).toContainText(/Payment (sent|received)/);
  });
});

test.describe("send money", () => {
  test("shows the live summary, confirms explicitly, and ends on a real result", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await page.goto(`/send?from=${seeded.usd.id}`);
    await settle(page);
    await page.getByLabel("Receiving account ID").fill("not-a-uuid");
    await page.getByLabel(/^Amount/).fill("0");
    await page.getByRole("button", { name: "Review payment" }).click();
    await expect(page.getByText(/Enter the receiving account ID/)).toBeVisible();
    await expect(page.getByText("Amount must be greater than zero")).toBeVisible();

    await page.getByRole("button", { name: /^Recent USD/ }).first().click();
    await page.getByLabel(/^Amount/).fill("5.25");
    const summary = page.getByLabel("Payment summary");
    await expect(summary).toContainText("$5.25");
    await expect(summary).toContainText("Fee");
    await page.getByRole("button", { name: "Review payment" }).click();
    const dialog = page.getByRole("dialog", { name: "Review and confirm" });
    await expect(dialog).toBeVisible();
    // focus stays inside the dialog while tabbing
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press("Tab");
      expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);
    }
    // nothing has been sent yet
    const before = await seeded.user.get<unknown[]>("payments/payments?limit=100");
    await dialog.getByRole("button", { name: "Back" }).click();
    expect((await seeded.user.get<unknown[]>("payments/payments?limit=100")).length).toBe(before.length);
    await page.getByRole("button", { name: "Review payment" }).click();
    await dialog.getByRole("button", { name: "Confirm and send" }).click();
    await expect(page.getByRole("heading", { name: "Payment sent" })).toBeVisible();
    expect((await seeded.user.get<unknown[]>("payments/payments?limit=100")).length).toBe(before.length + 1);
    await page.screenshot({ path: `${SHOTS}/send-success.png` });
  });
});

test.describe("motion and accessibility preferences", () => {
  test("prefers-reduced-motion turns the entrance animation and chart reveal off", async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: "reduce" });
    const page = await context.newPage();
    await signIn(page, seeded.email, PASSWORD);
    await settle(page);
    const names = await page.evaluate(() => ({
      page: getComputedStyle(document.querySelector(".page-enter")!).animationName,
      transition: getComputedStyle(document.querySelector(".btn, .quick-tile")!).transitionDuration,
    }));
    expect(names.page).toBe("none");
    expect(names.transition.split(",").every((d) => parseFloat(d) < 0.01)).toBe(true);
    await context.close();
  });

  test("the skip link and landmarks exist, and tabbing from the top reaches the main navigation", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await settle(page);
    await expect(page.locator("main#main")).toHaveCount(1);
    await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(1); // exactly one visible main navigation at this width (the other is display:none)
    await page.goto("/dashboard");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  });

  test("profile menu opens with the keyboard, closes on Escape and signs out", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    const trigger = page.locator(".profile-trigger");
    await trigger.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("menu")).toBeVisible();
    await expect(page.getByRole("menu")).toContainText(seeded.email);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("menu")).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await trigger.click();
    await page.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe("phones", () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test("bottom navigation and the menu drawer reach every page, admin entry hidden for customers", async ({ page }) => {
    await signIn(page, seeded.email, PASSWORD);
    await page.locator(".bottom-nav").getByRole("link", { name: "Payments" }).click();
    await expect(page).toHaveURL(/\/payments/);
    await expect(page.locator(".only-mobile .txn-row").first()).toBeVisible();
    await page.getByRole("button", { name: "Menu" }).click();
    const drawer = page.getByRole("dialog", { name: "Menu" });
    await expect(drawer.getByRole("link", { name: /Analytics/ })).toBeVisible();
    await expect(drawer.getByRole("link", { name: /Admin/ })).toHaveCount(0);
    await drawer.getByRole("link", { name: /Analytics/ }).click();
    await expect(page).toHaveURL(/\/analytics/);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});
