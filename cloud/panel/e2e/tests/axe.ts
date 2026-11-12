// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/** WCAG 2.0, 2.1 and 2.2 at A and AA, plus axe's best practices: a finding fails the test. */
const REQUIRED = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];
/**
 * The AAA rules axe has. Reported on every run, not failing: the brand's light-theme primary
 * (#00707F, 5.3:1 on the page background) meets AA but not the 7:1 of AAA (docs/cloud/panel.md).
 */
const AAA = ["wcag2aaa"];

const describe = (violations: { id: string; impact?: string | null; nodes: { target: unknown[] }[] }[]) =>
  violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);

export async function expectNoAxeViolations(page: Page) {
  const required = await new AxeBuilder({ page }).withTags(REQUIRED).analyze();
  expect(describe(required.violations), `axe (WCAG 2.2 AA) on ${page.url()}`).toEqual([]);

  const aaa = await new AxeBuilder({ page }).withTags(AAA).analyze();
  for (const line of describe(aaa.violations)) {
    test.info().annotations.push({ type: "wcag-aaa", description: `${new URL(page.url()).pathname}: ${line}` });
  }
}
