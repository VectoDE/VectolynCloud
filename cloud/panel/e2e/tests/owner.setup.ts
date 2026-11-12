// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import { expect, test } from "@playwright/test";
import { OWNER, SETUP_TOKEN } from "../playwright.config";
import { expectNoAxeViolations } from "./axe";

test("the first account is created with the setup token, and only once", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/setup\/$/);
  await expect(page.getByRole("heading", { level: 1, name: "Create the first account" })).toBeVisible();
  await expectNoAxeViolations(page);

  // A wrong token is refused and nothing is created.
  await page.getByLabel("Setup token").fill("vcs_wrongtoken000000000000000000");
  await page.getByLabel("User name").fill(OWNER.name);
  await page.getByLabel("Password", { exact: true }).fill(OWNER.password);
  await page.getByLabel("Repeat the password").fill(OWNER.password);
  await page.getByRole("button", { name: "Create the account" }).click();
  await expect(page.getByRole("alert").filter({ hasText: /./ })).toBeVisible();
  await expect(page).toHaveURL(/\/setup\/$/);

  await page.getByLabel("Setup token").fill(SETUP_TOKEN);
  await page.getByRole("button", { name: "Create the account" }).click();
  await expect(page).toHaveURL(/\/account\/$/);
  await page.context().storageState({ path: ".auth/owner.json" });
});
