import { expect, test, type Page } from "@playwright/test";
import { ADMIN_EMAIL, ADMIN_PASSWORD, PASSWORD, SHOTS, seedRichUser, signIn } from "./helpers";

/**
 * Renders every screen with real, seeded data at five widths and checks the layout contract:
 * no horizontal page scroll, and the navigation switches mode (sidebar, rail, bottom bar) at the documented points.
 * Screenshots go to e2e/screenshots/responsive/ for review.
 */
const WIDTHS = [
  { w: 1440, h: 900, nav: "sidebar" },
  { w: 1280, h: 800, nav: "sidebar" },
  { w: 1024, h: 768, nav: "rail" },
  { w: 768, h: 1024, nav: "rail" },
  { w: 390, h: 844, nav: "bottom" },
] as const;

test.skip(!ADMIN_PASSWORD, "set E2E_ADMIN_PASSWORD (printed by runLocal)");
test.describe.configure({ mode: "serial" });

let seeded: Awaited<ReturnType<typeof seedRichUser>>;
let paymentId = "";

test.beforeAll(async () => {
  seeded = await seedRichUser();
  const list = await seeded.user.get<{ id: string; reference: string | null }[]>("payments/payments?limit=20");
  paymentId = list.find((p) => p.reference === "Rent share")?.id ?? list[0]!.id;
});

async function settle(page: Page) {
  await page.waitForLoadState("networkidle");
  await expect(page.locator(".skeleton")).toHaveCount(0, { timeout: 15_000 });
}

async function expectNoHorizontalScroll(page: Page, label: string) {
  const overflow = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
  expect(overflow.scroll, `${label}: page scrolls sideways (${overflow.scroll} > ${overflow.client})`).toBeLessThanOrEqual(overflow.client + 1);
}

for (const vp of WIDTHS) {
  test(`customer screens at ${vp.w}px`, async ({ page }) => {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    await signIn(page, seeded.email, PASSWORD);
    // wait for the Kafka-delivered notifications so the notification screens show real items
    await expect.poll(async () => (await seeded.user.get<unknown[]>("notifications/notifications")).length, { timeout: 30_000 }).toBeGreaterThan(2);

    // navigation mode
    const sidebar = page.locator(".sidebar");
    const bottom = page.locator(".bottom-nav");
    if (vp.nav === "bottom") {
      await expect(sidebar).toBeHidden();
      await expect(bottom).toBeVisible();
    } else {
      await expect(bottom).toBeHidden();
      await expect(sidebar).toBeVisible();
      const width = (await sidebar.boundingBox())!.width;
      if (vp.nav === "sidebar") {
        expect(width).toBeGreaterThan(200);
        await expect(sidebar.locator(".nav-label").first()).toBeVisible();
      } else {
        expect(width).toBeLessThan(100);
        await expect(sidebar.locator(".nav-label").first()).toBeHidden();
      }
    }

    const pages: [string, string][] = [
      ["dashboard", "/dashboard"],
      ["accounts", "/accounts"],
      ["account-detail", `/accounts/${seeded.usd.id}`],
      ["send", "/send"],
      ["payments", "/payments"],
      ["payment-detail", `/payments/${paymentId}`],
      ["notifications", "/notifications"],
      ["analytics", "/analytics"],
    ];
    for (const [name, url] of pages) {
      await page.goto(url);
      await settle(page);
      await expectNoHorizontalScroll(page, `${name}@${vp.w}`);
      await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-${name}.png`, fullPage: true });
    }

    // overlays at this width
    await page.goto("/dashboard");
    await settle(page);
    if (vp.nav === "bottom") {
      await page.getByRole("button", { name: "Menu" }).click();
      await expect(page.getByRole("dialog", { name: "Menu" })).toBeVisible();
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-drawer.png` });
      await page.keyboard.press("Escape");
    }
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await page.getByRole("combobox").fill("rent");
    await expect(page.getByRole("option").first()).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-search.png` });
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: /^Notifications/ }).click();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-bell.png` });
    await page.keyboard.press("Escape");

    // the confirm dialog
    await page.goto(`/send?from=${seeded.usd.id}`);
    await settle(page);
    await page.getByLabel("Receiving account ID").fill(seeded.bobUsd.id);
    await page.getByLabel(/^Amount/).fill("12.34");
    await page.getByRole("button", { name: "Review payment" }).click();
    await expect(page.getByRole("dialog", { name: "Review and confirm" })).toBeVisible();
    await page.waitForTimeout(400); // let the 250ms entrance animation finish before the picture
    await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-confirm.png` });
    await page.keyboard.press("Escape");
  });
}

test("admin screen renders at every width without sideways scroll", async ({ page }) => {
  await signIn(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  for (const vp of WIDTHS) {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    await page.goto("/admin?tab=accounts");
    await settle(page);
    await expectNoHorizontalScroll(page, `admin@${vp.w}`);
    await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-admin.png`, fullPage: true });
  }
});

test("sign-in and register render at every width without sideways scroll", async ({ page }) => {
  for (const vp of WIDTHS) {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    for (const url of ["/login", "/register"]) {
      await page.goto(url);
      await expectNoHorizontalScroll(page, `${url}@${vp.w}`);
    }
    await page.screenshot({ path: `${SHOTS}/responsive/${vp.w}-register.png`, fullPage: true });
  }
});
