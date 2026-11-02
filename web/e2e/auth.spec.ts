import { expect, test, type Page } from "@playwright/test";
import { PASSWORD, SHOTS, uid } from "./helpers";

/** The sign-in screens: composition, validation, interaction details and responsive behaviour. */

const WIDTHS = [1440, 1280, 1024, 768, 390] as const;
const box = async (page: Page, selector: string) => (await page.locator(selector).first().boundingBox())!;

test.describe("composition", () => {
  test("is asymmetric: a narrow form on the grid's left edge, the sheet right and lower, none of the template furniture", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/login");
    const logo = await box(page, ".auth-home");
    const panel = await box(page, ".auth-panel");
    const aside = await box(page, ".auth-aside");
    const sheet = await box(page, ".ledger");
    // the form shares the logo's left edge and is narrow (not half the page)
    expect(Math.abs(panel.x - logo.x)).toBeLessThanOrEqual(2);
    expect(panel.width).toBeLessThanOrEqual(400);
    expect(panel.width / 1440).toBeLessThan(0.3);
    // the sheet sits to the right and starts lower than the form's heading
    expect(sheet.x).toBeGreaterThan(panel.x + panel.width + 80);
    expect(aside.y).toBeGreaterThan(panel.y + 30);
    // the sheet ends at the same right edge as the header's content
    const prompt = await box(page, ".auth-prompt");
    expect(Math.abs(sheet.x + sheet.width - (prompt.x + prompt.width))).toBeLessThanOrEqual(2);
    // no full-height split panel, no checklist, no banner, no gradients
    await expect(page.locator(".auth-pitch, .sandbox-banner, .auth-split")).toHaveCount(0);
    await expect(page.locator("main li svg")).toHaveCount(0);
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bg).toBe("rgb(248, 248, 244)");
    const gradients = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>("body *")].filter((e) => getComputedStyle(e).backgroundImage.includes("gradient")).length);
    expect(gradients).toBe(0);
    // header is slim
    expect((await box(page, ".auth-header")).height).toBeLessThanOrEqual(58);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Welcome back.");
    await expect(page.getByText("Sign in to continue to your PayNexa account.")).toBeVisible();
    await expect(page.getByText("Secure sandbox environment · Synthetic funds only")).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/auth-after/login-1440.png` });
  });

  test("only offers what the backend supports", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByText(/forgot/i)).toHaveCount(0);
    await expect(page.getByText(/remember me/i)).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/register");
  });

  for (const w of WIDTHS) {
    test(`login and register hold together at ${w}px`, async ({ page }) => {
      await page.setViewportSize({ width: w, height: w < 700 ? 844 : 900 });
      for (const url of ["/login", "/register"]) {
        await page.goto(url);
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${url}@${w} scrolls sideways`).toBeLessThanOrEqual(0);
        await expect(page.locator(".auth-home")).toBeVisible();
        await expect(page.getByText("Secure sandbox environment · Synthetic funds only")).toBeVisible();
        const panel = await box(page, ".auth-panel");
        expect(panel.width).toBeLessThanOrEqual(430);
        // inputs and the button are comfortable touch targets
        const input = await box(page, "#email");
        expect(input.height).toBeGreaterThanOrEqual((w < 768 ? 44 : 40) - 0.5);
        const btn = await box(page, ".btn-auth");
        expect(btn.height).toBeGreaterThanOrEqual((w < 640 ? 48 : 40) - 0.5);
        if (w >= 1000) {
          const sheet = await box(page, ".ledger");
          expect(sheet.x).toBeGreaterThan(panel.x + panel.width);
        } else if (w >= 640) {
          const sheet = await box(page, ".ledger");
          expect(sheet.y).toBeGreaterThan(panel.y + panel.height); // the band sits below the form
        } else {
          await expect(page.locator(".ledger")).toBeHidden();
        }
        await page.screenshot({ path: `${SHOTS}/auth-after/${url.slice(1)}-${w}.png`, fullPage: true });
      }
    });
  }
});

test.describe("validation and feedback", () => {
  test("shows errors inline on submit, focuses the first invalid field, and clears them as the user fixes them", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText("Enter a valid email address")).toBeVisible();
    await expect(page.getByText("Enter your password")).toBeVisible();
    await expect(page.locator("#email")).toBeFocused();
    await expect(page.locator("#email")).toHaveAttribute("aria-invalid", "true");
    await expect(page.locator("#email")).toHaveAttribute("aria-describedby", "email-error");
    await page.locator("#email").fill("someone@example.com");
    await expect(page.getByText("Enter a valid email address")).toHaveCount(0);
    await page.locator("#password").fill("x");
    await expect(page.getByText("Enter your password")).toHaveCount(0);
  });

  test("validates an email when the user leaves the field, not while they are still typing it", async ({ page }) => {
    await page.goto("/login");
    await page.locator("#email").fill("jane@");
    await expect(page.getByText("Enter a valid email address")).toHaveCount(0);
    await page.locator("#password").focus();
    await expect(page.getByText("Enter a valid email address")).toBeVisible();
  });

  test("register counts characters toward twelve and says why a short password is refused", async ({ page }) => {
    await page.goto("/register");
    await expect(page.getByText("At least 12 characters. A passphrase works well.")).toBeVisible();
    await page.locator("#password").fill("abcdef");
    await expect(page.getByText("6 / 12")).toBeVisible();
    await page.locator("#email").fill(`short-${uid()}@example.com`);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByText("Use at least 12 characters")).toBeVisible();
    await page.locator("#password").fill("abcdefghijkl");
    await expect(page.getByText("12 / 12")).toHaveClass(/met/);
  });

  test("wrong credentials: a clear message, password cleared and re-hidden, focus returned to the password", async ({ page }) => {
    await page.goto("/login");
    await page.locator("#email").fill(`nobody-${uid()}@example.com`);
    await page.locator("#password").fill("definitely wrong password");
    await page.getByRole("button", { name: "Show password" }).click();
    await expect(page.locator("#password")).toHaveAttribute("type", "text");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "That email and password do not match." })).toBeVisible();
    await expect(page.locator("#password")).toHaveValue("");
    await expect(page.locator("#password")).toHaveAttribute("type", "password");
    await expect(page.locator("#password")).toBeFocused();
    await expect(page.getByText("Enter your password")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();
    await expect(page).toHaveURL(/\/login/);
    await page.screenshot({ path: `${SHOTS}/auth-after/login-error.png` });
  });

  test("shows a busy button while the request is in flight and refuses a second submit", async ({ page }) => {
    let calls = 0;
    await page.route("**/api/auth/login", async (route) => {
      calls++;
      await new Promise((r) => setTimeout(r, 700));
      await route.continue();
    });
    await page.goto("/login");
    await page.locator("#email").fill(`slow-${uid()}@example.com`);
    await page.locator("#password").fill("some password here");
    await page.locator("#password").press("Enter");
    const button = page.getByRole("button", { name: "Signing in…" });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAttribute("aria-busy", "true");
    await expect(button.locator(".spinner")).toBeVisible();
    await page.locator("#password").press("Enter"); // a second Enter must not send a second request
    await expect(page.getByRole("alert").filter({ hasText: "do not match" })).toBeVisible();
    expect(calls).toBe(1);
  });
});

test.describe("interaction details", () => {
  test("password toggle: a real button that reports its state and is reachable by keyboard in a sensible order", async ({ page }) => {
    await page.goto("/login");
    await page.locator("#email").focus();
    await page.keyboard.press("Tab");
    await expect(page.locator("#password")).toBeFocused();
    await page.keyboard.press("Tab");
    const toggle = page.getByRole("button", { name: "Show password" });
    await expect(toggle).toBeFocused();
    await page.keyboard.press("Space");
    await expect(page.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#password")).toHaveAttribute("type", "text");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Sign in" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Create an account" })).toBeFocused();
  });

  test("focus is visible and uses the brand colour on inputs and the button", async ({ page }) => {
    await page.goto("/login");
    await page.locator("#email").focus();
    await page.waitForTimeout(300); // the border colour eases over 150ms
    const input = await page.locator("#email").evaluate((e) => { const c = getComputedStyle(e); return { border: c.borderColor, shadow: c.boxShadow }; });
    expect(input.border).toBe("rgb(79, 125, 99)"); // --accent-500
    expect(input.shadow).not.toBe("none");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    const btn = await page.getByRole("button", { name: "Sign in" }).evaluate((e) => getComputedStyle(e).boxShadow);
    expect(btn).not.toBe("none");
  });

  test("the primary button is brand green with distinct hover and pressed colours", async ({ page }) => {
    await page.goto("/login");
    const button = page.getByRole("button", { name: "Sign in" });
    const colour = () => button.evaluate((e) => getComputedStyle(e).backgroundColor);
    const rest = await colour();
    expect(rest).toBe("rgb(49, 91, 70)"); // #315B46
    await button.hover();
    await page.waitForTimeout(250);
    const hover = await colour();
    expect(hover).not.toBe(rest);
    await page.mouse.down();
    await page.waitForTimeout(250);
    const pressed = await colour();
    await page.mouse.up();
    expect(pressed).not.toBe(hover);
  });

  test("the header link and the form link both carry the page the visitor was heading to", async ({ page }) => {
    await page.goto("/payments");
    await expect(page).toHaveURL(/\/login\?next=%2Fpayments/);
    await expect(page.locator(".auth-prompt a")).toHaveAttribute("href", "/register?next=%2Fpayments");
    await expect(page.locator(".auth-switch a")).toHaveAttribute("href", "/register?next=%2Fpayments");
    await page.locator(".auth-prompt a").click();
    await expect(page).toHaveURL(/\/register\?next=%2Fpayments/);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Create your account.");
    await expect(page.locator(".auth-prompt a")).toHaveText("Sign in");
  });

  test("registering lands on the page the visitor wanted, and an off-site next is ignored", async ({ page }) => {
    await page.goto("/register?next=https://evil.example/steal");
    await page.locator("#email").fill(`new-${uid()}@example.com`);
    await page.locator("#password").fill(PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page).toHaveURL(/\/dashboard/);
    expect(new URL(page.url()).hostname).not.toBe("evil.example");
    // and now signed in, the sign-in page sends you onward instead of showing the form again
    await page.goto("/login");
    await expect(page).toHaveURL(/\/dashboard/);
  });

  test("the header stays put and only the content transitions between sign in and register", async ({ page }) => {
    await page.goto("/login");
    await page.locator(".auth-header").evaluate((e) => ((e as HTMLElement).dataset.marker = "kept"));
    await page.locator(".auth-switch a").click();
    await expect(page).toHaveURL(/\/register/);
    expect(await page.locator(".auth-header").getAttribute("data-marker")).toBe("kept");
  });

  test("prefers-reduced-motion removes the entrance and the toggle/field transitions", async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: "reduce" });
    const page = await context.newPage();
    await page.goto("/login");
    expect(await page.locator(".auth-stage").evaluate((e) => getComputedStyle(e).animationName)).toBe("none");
    expect(await page.locator("#email").evaluate((e) => parseFloat(getComputedStyle(e).transitionDuration))).toBeLessThan(0.01);
    await context.close();
  });
});
