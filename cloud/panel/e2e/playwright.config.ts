// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import { defineConfig } from "@playwright/test";
import { join, resolve } from "node:path";

// The tests run against the panel preview (cloud/panel/src/test/.../PanelPreview.java): the real
// panel server and the built frontend, with a made-up node behind it. The setup token is fixed
// through VECTOLYN_PREVIEW_SETUP_TOKEN, so nothing has to be read from the server's output.
export const PORT = Number(process.env.PANEL_E2E_PORT ?? 8095);
export const BASE = `http://localhost:${PORT}`;
export const SETUP_TOKEN = process.env.VECTOLYN_PREVIEW_SETUP_TOKEN ?? "vcs_e2eonlytoken0000000000000000";
export const OWNER = { name: "owner", password: "e2e-Passwort-only-2026" };

const ROOT = resolve(process.cwd(), "../../..");
const gradle = process.platform === "win32" ? `"${join(ROOT, "gradlew.bat")}"` : "./gradlew";
// CI uses the Chrome on the runner (no browser download); locally any installed Chrome works.
const channel = process.env.PANEL_E2E_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "tests",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  use: {
    baseURL: BASE,
    channel,
    locale: "en-US",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /setup\.ts$/ },
    { name: "panel", testMatch: /\.spec\.ts$/, dependencies: ["setup"], use: { storageState: ".auth/owner.json" } },
  ],
  webServer: {
    command: `${gradle} :cloud:panel:panelPreview -Pvectolyn.panel.web=true -PpanelPort=${PORT} --no-daemon --console=plain`,
    cwd: ROOT,
    url: `${BASE}/`,
    timeout: 15 * 60_000,
    reuseExistingServer: false,
    stdout: "ignore",
    stderr: "pipe",
    env: { VECTOLYN_PREVIEW_SETUP_TOKEN: SETUP_TOKEN },
  },
});
