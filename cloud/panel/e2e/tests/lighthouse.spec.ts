// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import { chromium, expect, test } from "@playwright/test";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import lighthouse from "lighthouse";
import { BASE } from "../playwright.config";
import { PAGES } from "./pages";

// Lighthouse in the same Chrome as the tests, signed in with the owner's session: Playwright opens
// Chrome with a debugging port, Lighthouse connects to it. Storage is not reset, so the session
// cookie stays. Reports land in lighthouse-report/ (an artifact of the CI run).
const DEBUG_PORT = 9333;
const MINIMUM = { accessibility: 1, "best-practices": 1 } as const;
const MAX_CLS = 0.1;

test.describe.configure({ mode: "serial" });
test.setTimeout(10 * 60_000);

test("Lighthouse: accessibility and best practices 100, layout shift under 0.1, on every page", async () => {
  const profile = mkdtempSync(join(tmpdir(), "vectolyn-lh-"));
  const context = await chromium.launchPersistentContext(profile, {
    channel: process.env.PANEL_E2E_CHANNEL ?? "chrome",
    headless: true,
    locale: "en-US",
    args: [`--remote-debugging-port=${DEBUG_PORT}`],
  });
  const state = JSON.parse(readFileSync(".auth/owner.json", "utf-8"));
  await context.addCookies(state.cookies);
  mkdirSync("lighthouse-report", { recursive: true });

  const summary: string[] = [];
  const failures: string[] = [];
  try {
    for (const page of PAGES) {
      // A fresh tab each time: Lighthouse traces the load of the page it is attached to, and a tab
      // that already held a page (or a live WebSocket) can lose the trace start (NO_NAVSTART).
      let result: Awaited<ReturnType<typeof lighthouse>> | undefined;
      for (let attempt = 0; attempt < 3; attempt++) {
        const fresh = await context.newPage();
        for (const old of context.pages()) if (old !== fresh) await old.close();
        const run = await lighthouse(`${BASE}${page.path}`, {
          port: DEBUG_PORT,
          output: "html",
          logLevel: "error",
          onlyCategories: ["accessibility", "best-practices", "performance"],
          formFactor: "desktop",
          screenEmulation: { mobile: false, width: 1350, height: 940, deviceScaleFactor: 1, disabled: false },
          throttlingMethod: "provided",
          disableStorageReset: true,
        });
        result = run;
        if (!run?.lhr.runtimeError) break;
      }
      if (!result) throw new Error(`no Lighthouse result for ${page.path}`);
      const name = page.path.replace(/[^a-z0-9]+/gi, "-").replace(/^-|-$/g, "") || "start";
      writeFileSync(join("lighthouse-report", `${name}.html`), result.report as string);
      const lhr = result.lhr;
      const cls = Number(lhr.audits["cumulative-layout-shift"]?.numericValue ?? 0);
      const line = `${page.path}: accessibility ${Math.round((lhr.categories.accessibility.score ?? 0) * 100)}, `
        + `best practices ${Math.round((lhr.categories["best-practices"].score ?? 0) * 100)}, `
        + `performance ${Math.round((lhr.categories.performance.score ?? 0) * 100)}, CLS ${cls.toFixed(3)}`;
      summary.push(line);
      if (lhr.runtimeError) {
        summary.push(`    runtime error ${lhr.runtimeError.code}: ${lhr.runtimeError.message}`);
        failures.push(`${page.path} Lighthouse ${lhr.runtimeError.code}`);
      }
      for (const w of lhr.runWarnings ?? []) summary.push(`    warning: ${w}`);
      const shifts = (lhr.audits["layout-shifts"]?.details as { items?: { score?: number; node?: { snippet?: string } }[] } | undefined)?.items ?? [];
      for (const s of shifts.slice(0, 3)) summary.push(`    shift ${Number(s.score ?? 0).toFixed(3)}: ${s.node?.snippet ?? "?"}`);
      for (const [category, minimum] of Object.entries(MINIMUM)) {
        if ((lhr.categories[category].score ?? 0) < minimum) failures.push(`${page.path} ${category}`);
      }
      if (cls >= MAX_CLS) failures.push(`${page.path} CLS ${cls.toFixed(3)}`);
    }
  } finally {
    writeFileSync(join("lighthouse-report", "summary.txt"), summary.join("\n") + "\n");
    await context.close();
  }
  console.log(summary.join("\n"));
  expect(failures).toEqual([]);
});
