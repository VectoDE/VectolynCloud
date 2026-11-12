// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Copies the design tokens and the fonts from branding/ into the panel before a build, so the
// panel uses the same files as the website and there is no second copy in the repository.
// The copies are ignored by git (cloud/panel/web/.gitignore).
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const web = join(dirname(fileURLToPath(import.meta.url)), "..");
const branding = join(web, "..", "..", "..", "branding");

const files = [
  ["tokens.css", join(web, "app", "brand-tokens.css")],
  ["mark-on-dark.svg", join(web, "public", "mark-on-dark.svg")],
  ["mark-on-light.svg", join(web, "public", "mark-on-light.svg")],
  ["fonts/atkinson-hyperlegible-next/AtkinsonHyperlegibleNext-Variable.ttf",
    join(web, "public", "fonts", "AtkinsonHyperlegibleNext-Variable.ttf")],
  ["fonts/jetbrains-mono/JetBrainsMono-Variable.ttf", join(web, "public", "fonts", "JetBrainsMono-Variable.ttf")],
  ["fonts/pixelify-sans/PixelifySans-Bold.ttf", join(web, "public", "fonts", "PixelifySans-Bold.ttf")],
];

for (const [from, to] of files) {
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(join(branding, from), to);
}
console.log(`sync-brand: ${files.length} files from branding/`);
