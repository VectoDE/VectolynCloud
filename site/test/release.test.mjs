// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tests for the release lookup of the download page (src/release.mjs) and for what the page renders
// from it. No network: fetch is replaced by a stub that serves the fixtures in fixtures/
// (the real API answer and SHA256SUMS of v0.1.2-dev, trimmed to the fields the build reads).
// Run with: node --test site/test/

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveRelease, releaseModel, parseSums } from '../src/release.mjs';
import { pages } from '../src/pages.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const REPO = 'https://github.com/VectoDE/VectolynCloud';
const RELEASES = JSON.parse(readFileSync(join(here, 'fixtures', 'releases.json'), 'utf8'));
const SUMS = readFileSync(join(here, 'fixtures', 'SHA256SUMS'), 'utf8');
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

let siteDir, realFetch, calls;
const clone = (x) => JSON.parse(JSON.stringify(x));

/** Replaces fetch: the releases list for the API, the given SHA256SUMS text for the release file. */
function serve({ releases = RELEASES, sums = SUMS, apiStatus = 200 } = {}) {
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).startsWith('https://api.github.com/')) {
      return new Response(JSON.stringify(releases), { status: apiStatus });
    }
    if (String(url).endsWith('/SHA256SUMS')) return new Response(sums, { status: 200 });
    return new Response('not found', { status: 404 });
  };
}

function render(release, lang = 'de') {
  return pages.download(lang, { release }).body({ esc, link: () => '#', config: { release } });
}

beforeEach(() => {
  siteDir = mkdtempSync(join(tmpdir(), 'vectolyn-site-'));
  mkdirSync(join(siteDir, 'src'));
  realFetch = globalThis.fetch;
  calls = [];
});
afterEach(() => {
  globalThis.fetch = realFetch;
  rmSync(siteDir, { recursive: true, force: true });
});

test('SHA256SUMS: reads both line formats and ignores everything else', () => {
  const sums = parseSums(`${'a'.repeat(64)}  one.zip\n${'b'.repeat(64)} *two.jar\nnot a line\n${'c'.repeat(63)}  short.txt\n`);
  assert.deepEqual(sums, { 'one.zip': 'a'.repeat(64), 'two.jar': 'b'.repeat(64) });
});

test('pre-release: the newest release is used, marked as pre-release, checksums come from SHA256SUMS', async () => {
  serve();
  const r = await resolveRelease(REPO, { siteDir });
  assert.equal(r.tag, 'v0.1.2-dev');
  assert.equal(r.version, '0.1.2-dev');
  assert.equal(r.prerelease, true);
  const sums = parseSums(SUMS);
  for (const f of Object.values(r.files)) {
    assert.ok(f, 'every expected file is in the fixture release');
    assert.equal(f.sha256, sums[f.name]);
    assert.equal(f.url, `${REPO}/releases/download/v0.1.2-dev/${f.name}`);
  }
  assert.ok(existsSync(join(siteDir, 'src', 'release.json')), 'a good result is cached');
  const html = render(r);
  assert.match(html, /Vorabversion/);
  assert.match(html, new RegExp(r.files.windows.sha256));
  assert.match(html, /href="#pruefen">Signatur prüfen/);
});

test('drafts and releases with foreign links are skipped', async () => {
  const draft = { ...clone(RELEASES[0]), tag_name: 'v9.9.9', html_url: `${REPO}/releases/tag/v9.9.9`, draft: true };
  serve({ releases: [draft, ...RELEASES] });
  assert.equal((await resolveRelease(REPO, { siteDir })).tag, 'v0.1.2-dev');
  const rel = clone(RELEASES[0]);
  rel.assets.push({ name: 'evil.zip', size: 1, browser_download_url: 'https://example.org/evil.zip' });
  const m = releaseModel(rel, SUMS, REPO);
  assert.ok(!JSON.stringify(m).includes('example.org'));
});

test('missing asset: notice instead of a link, the build goes on', async () => {
  const rel = clone(RELEASES[0]);
  rel.assets = rel.assets.filter((a) => !a.name.endsWith('-windows.zip'));
  serve({ releases: [rel] });
  const r = await resolveRelease(REPO, { siteDir });
  assert.equal(r.files.windows, null);
  assert.ok(r.files.unix && r.files.jar);
  const html = render(r);
  assert.ok(!html.includes('-windows.zip'), 'no link to the missing file');
  assert.match(html, /fehlt die Datei für Windows/);
  assert.match(html, /fehlt in diesem Release/);
  assert.match(render(r, 'en'), /has no file for Windows/);
});

test('asset without checksum: marked, never computed', async () => {
  serve({ sums: SUMS.split('\n').filter((l) => !l.endsWith(' VectolynCloud.jar')).join('\n') });
  const r = await resolveRelease(REPO, { siteDir });
  assert.equal(r.files.jar.sha256, null);
  assert.ok(r.files.windows.sha256);
  assert.match(render(r), /nicht in SHA256SUMS/);
});

test('release without SHA256SUMS: every checksum is empty', async () => {
  const rel = clone(RELEASES[0]);
  rel.assets = rel.assets.filter((a) => a.name !== 'SHA256SUMS');
  serve({ releases: [rel] });
  const r = await resolveRelease(REPO, { siteDir });
  assert.equal(r.sumsUrl, null);
  assert.ok(Object.values(r.files).every((f) => f.sha256 === null));
  assert.ok(!calls.some((u) => u.endsWith('/SHA256SUMS')), 'nothing to download');
});

test('no release data: no releases, API error or no network all end in the fallback', async () => {
  serve({ releases: [] });
  assert.equal(await resolveRelease(REPO, { siteDir }), null);
  serve({ apiStatus: 503 });
  assert.equal(await resolveRelease(REPO, { siteDir }), null);
  globalThis.fetch = async () => { throw new TypeError('fetch failed'); };
  assert.equal(await resolveRelease(REPO, { siteDir }), null);
  const html = render(null);
  assert.match(html, /Release-Daten konnten beim Bauen dieser Seite nicht geladen werden/);
  assert.match(html, /href="https:\/\/github.com\/VectoDE\/VectolynCloud\/releases"/);
});

test('offline copy: SITE_OFFLINE and a failing GitHub both use release.json', async () => {
  const cached = releaseModel(RELEASES[1], null, REPO);
  writeFileSync(join(siteDir, 'src', 'release.json'), JSON.stringify(cached));
  globalThis.fetch = async () => { throw new Error('must not be called offline'); };
  assert.deepEqual(await resolveRelease(REPO, { siteDir, offline: true }), cached);
  serve({ apiStatus: 500 });
  assert.equal((await resolveRelease(REPO, { siteDir })).tag, 'v0.1.1-dev');
  assert.equal(await resolveRelease(REPO, { siteDir: mkdtempSync(join(tmpdir(), 'vectolyn-empty-')), offline: true }), null);
});
