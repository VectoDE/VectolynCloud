// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import { expect, test } from "@playwright/test";
import { expectNoAxeViolations } from "./axe";
import { PAGES } from "./pages";


for (const language of ["en", "de"] as const) {
  test.describe(`pages in ${language}`, () => {
    test.use({ locale: language === "en" ? "en-US" : "de-DE" });

    for (const page of PAGES) {
      test(`${page.path} has no axe findings`, async ({ page: tab }) => {
        await tab.goto(page.path);
        const heading = tab.getByRole("heading", { level: 1 });
        await expect(heading).toBeVisible();
        if (language === "en") await expect(heading).toHaveText(page.heading);
        await expect(tab.locator("html")).toHaveAttribute("lang", language);
        if (page.ready) await expect(tab.getByText(page.ready).first()).toBeVisible();
        await expectNoAxeViolations(tab);
      });
    }
  });
}

test("the sign-in page has no axe findings and refuses a wrong password", async ({ browser }) => {
  const context = await browser.newContext({ locale: "en-US", storageState: { cookies: [], origins: [] } });
  const tab = await context.newPage();
  await tab.goto("/login/");
  await expect(tab.getByRole("heading", { level: 1, name: "Sign in" })).toBeVisible();
  await expectNoAxeViolations(tab);
  await tab.getByLabel("User name").fill("owner");
  await tab.getByLabel("Password").fill("not-the-password");
  await tab.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(tab.getByRole("alert").filter({ hasText: /./ })).toBeVisible();
  await expect(tab).toHaveURL(/\/login\/$/);
  await context.close();
});

test("a signed-out visitor is sent to the sign-in page", async ({ browser }) => {
  const context = await browser.newContext({ locale: "en-US", storageState: { cookies: [], origins: [] } });
  const tab = await context.newPage();
  await tab.goto("/users/");
  await expect(tab).toHaveURL(/\/login\/$/);
  await context.close();
});
