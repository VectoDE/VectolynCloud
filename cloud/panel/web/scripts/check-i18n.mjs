// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Fails when the German and the English catalogue do not have exactly the same keys, when a value
// is empty, or when the {placeholders} of a key differ between the languages.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "messages");
const de = JSON.parse(readFileSync(join(dir, "de.json"), "utf8"));
const en = JSON.parse(readFileSync(join(dir, "en.json"), "utf8"));
const problems = [];
const placeholders = (text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(",");

for (const key of new Set([...Object.keys(de), ...Object.keys(en)])) {
  if (!(key in de)) problems.push(`missing in de.json: ${key}`);
  else if (!(key in en)) problems.push(`missing in en.json: ${key}`);
  else if (!de[key].trim() || !en[key].trim()) problems.push(`empty value: ${key}`);
  else if (placeholders(de[key]) !== placeholders(en[key])) problems.push(`placeholders differ: ${key}`);
}
if (problems.length) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log(`check-i18n: ${Object.keys(en).length} keys in de and en`);
