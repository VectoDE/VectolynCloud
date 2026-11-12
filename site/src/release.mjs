// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Newest release for the download page, read once at build time so visitors never query GitHub.
// Version, date and asset links come from the GitHub API (pre-releases included, drafts never);
// the SHA-256 of every file is read from the release's own SHA256SUMS, never computed here.
// The files themselves stay on GitHub: the page links to their release download URLs.
// A good result is cached in release.json next to this file. When GitHub cannot be reached the
// cache is used; without a cache the page falls back to a plain link to the releases page.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const UA = { 'user-agent': 'vectolyn-site-build' };
const TAG = /^v[0-9A-Za-z.-]{1,40}$/;
const NAME = /^[A-Za-z0-9._-]{1,100}$/;
const SUMS_MAX = 64 * 1024;

/** The files the download page offers. A missing one becomes a notice, never a dead link. */
export const KINDS = [
  { id: 'windows', match: (n) => /^VectolynCloud-.+-windows\.zip$/.test(n) },
  { id: 'unix', match: (n) => /^VectolynCloud-.+-linux-macos\.tar\.gz$/.test(n) },
  { id: 'jar', match: (n) => n === 'VectolynCloud.jar' },
  { id: 'templates', match: (n) => /^VectolynCloud-.+-templates\.tar\.gz$/.test(n) },
  { id: 'setup', match: (n) => n === 'setup.example.yml' },
];

/** Parses SHA256SUMS ("<hex>  <name>" or "<hex> *<name>") into { name: hex }. */
export function parseSums(text) {
  const sums = {};
  for (const line of text.split(/\r?\n/)) {
    const m = /^([0-9a-f]{64}) [ *]([A-Za-z0-9._-]{1,100})$/.exec(line.trim());
    if (m) sums[m[2]] = m[1];
  }
  return sums;
}

/** Builds the page model from a GitHub release object and the text of its SHA256SUMS (or null). */
export function releaseModel(r, sumsText, repo) {
  const prefix = `${repo}/releases/download/${r.tag_name}/`;
  const assets = (r.assets || []).filter((a) => NAME.test(a.name) && a.browser_download_url === prefix + a.name);
  const sums = sumsText ? parseSums(sumsText) : {};
  const names = new Set(assets.map((a) => a.name));
  const files = {};
  for (const k of KINDS) {
    const a = assets.find((x) => k.match(x.name));
    files[k.id] = a ? {
      name: a.name, url: a.browser_download_url, size: a.size,
      sha256: sums[a.name] || null,
      sigstore: names.has(a.name + '.sigstore.json') ? prefix + a.name + '.sigstore.json' : null,
    } : null;
  }
  return {
    tag: r.tag_name, version: r.tag_name.replace(/^v/, ''), date: (r.published_at || '').slice(0, 10),
    prerelease: !!r.prerelease, url: r.html_url,
    sumsUrl: names.has('SHA256SUMS') ? prefix + 'SHA256SUMS' : null,
    provenanceUrl: names.has('vectolyn.intoto.jsonl') ? prefix + 'vectolyn.intoto.jsonl' : null,
    files,
  };
}

async function fetchLatest(repo) {
  const repoPath = new URL(repo).pathname.replace(/^\/|\/$/g, '');
  const headers = { ...UA, accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(`https://api.github.com/repos/${repoPath}/releases?per_page=10`, { headers, signal: AbortSignal.timeout(8000) });
  if (!res.ok) throw new Error(`api.github.com answered ${res.status}`);
  const r = (await res.json()).find((x) => !x.draft && TAG.test(x.tag_name) && x.html_url === `${repo}/releases/tag/${x.tag_name}`);
  if (!r) return null;
  let sumsText = null;
  const sums = (r.assets || []).find((a) => a.name === 'SHA256SUMS');
  if (sums && sums.size <= SUMS_MAX && sums.browser_download_url === `${repo}/releases/download/${r.tag_name}/SHA256SUMS`) {
    // No token here: the file is public and the request is redirected to GitHub's download host.
    const s = await fetch(sums.browser_download_url, { headers: UA, redirect: 'follow', signal: AbortSignal.timeout(8000) });
    if (!s.ok) throw new Error(`SHA256SUMS answered ${s.status}`);
    sumsText = (await s.text()).slice(0, SUMS_MAX);
  }
  return releaseModel(r, sumsText, repo);
}

export async function resolveRelease(repo, { siteDir, offline = false }) {
  const cacheFile = join(siteDir, 'src', 'release.json');
  const cached = existsSync(cacheFile) ? JSON.parse(readFileSync(cacheFile, 'utf8')) : null;
  if (offline) return cached;
  try {
    const model = await fetchLatest(repo);
    if (model) {
      writeFileSync(cacheFile, JSON.stringify(model, null, 2) + '\n');
      return model;
    }
    return cached;
  } catch (e) {
    console.warn(`release: ${e.message}; ${cached ? 'using the cached ' + cached.tag : 'no release on the page'}`);
    return cached;
  }
}
