// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import { expect, test } from "@playwright/test";

test("the console shows live output from the WebSocket", async ({ page }) => {
  await page.goto("/services/console/?id=lobby-1");
  const log = page.getByRole("log");
  // The preview sends one line a second; two must arrive.
  await expect(log).toContainText("[preview] line", { timeout: 5_000 });
  const first = await log.innerText();
  await expect.poll(async () => (await log.innerText()).length, { timeout: 5_000 }).toBeGreaterThan(first.length);
});

test("a file is opened, changed and saved, and the change is read back", async ({ page }) => {
  await page.goto("/services/files/?id=lobby-1");
  await page.getByRole("button", { name: "server.properties", exact: true }).click();
  const editor = page.getByRole("textbox", { name: /Editing/ });
  await expect(editor).toHaveValue(/motd=Preview/);
  await editor.fill("motd=Changed by the end-to-end test\nmax-players=50\n");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("server.properties was saved.")).toBeVisible();

  await page.reload();
  await page.getByRole("button", { name: "server.properties", exact: true }).click();
  await expect(page.getByRole("textbox", { name: /Editing/ })).toHaveValue(/Changed by the end-to-end test/);
});

test("the log search finds lines and lands in the audit log", async ({ page }) => {
  await page.goto("/activity/");
  await page.getByRole("searchbox", { name: /Search text/ }).fill("Service");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page.getByText("2 matching lines")).toBeVisible();

  await page.reload();
  await page.getByRole("searchbox", { name: /Filter by event/ }).fill("log.searched");
  await expect(page.getByRole("region", { name: "Audit log entries" }).getByText("log.searched")).toBeVisible();
});

test("the language can be switched and is kept", async ({ page }) => {
  await page.goto("/services/");
  await page.getByRole("combobox", { name: "Language" }).selectOption({ label: "Deutsch" });
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Dienste");
  await page.reload();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Dienste");
  await expect(page.locator("html")).toHaveAttribute("lang", "de");
  await page.getByRole("combobox", { name: "Sprache" }).selectOption({ label: "English" });
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Services");
});

test("the skip link moves the focus to the content", async ({ page }) => {
  await page.goto("/services/");
  await page.keyboard.press("Tab");
  const skip = page.getByRole("link", { name: /Skip to content/ });
  await expect(skip).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/#content$/);
});
