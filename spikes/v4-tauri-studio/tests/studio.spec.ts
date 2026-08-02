import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test("shell remains operable at the configured scale", async ({ page }, testInfo) => {
  await page.goto("/");
  await expect(page).toHaveTitle("OpenStream Studio feasibility spike");
  await expect(page.getByRole("article")).toHaveCount(4);
  await expect(page.getByText("Browser-only simulated bridge")).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Use dark theme" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("button", { name: "Use light theme" })).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("shell.png") });
});

test("light theme and reduced motion infrastructure are active", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Use dark theme" })).toBeVisible();
  const duration = await page.locator("button").first().evaluate(element => getComputedStyle(element).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThanOrEqual(0.00001);
});
