import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests drive a real browser against a RUNNING stack:
 *   1. ./gradlew :system-tests:runLocal            (backend: prints the admin password)
 *   2. npm run build && npx next start -p 3100      (this app)
 *   3. E2E_ADMIN_PASSWORD='<printed>' E2E_BASE_URL=http://localhost:3100 npm run e2e
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3100",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
