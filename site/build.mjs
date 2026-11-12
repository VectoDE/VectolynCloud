// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Builds the public website into build/pages/ (German under /de/, English under /en/).
// No dependencies: plain Node.js (18 or newer). Run through site/build.sh.
//
// Settings live in site/site.config.json. Every value that is null stays off the site:
//   testNetwork.address / bedrockPort  address of the test network; null shows "address follows"
//   testNetwork.statusUrl              JSON endpoint {"online":bool,"players":int,"max":int}; null = no live query
//   contactEmail                       e-mail address in the imprint; null shows a marked placeholder
//   analytics.gtmId                    Google Tag Manager container; null = no banner, no tag, nothing loaded
//   analytics.searchConsoleToken       content of the google-site-verification meta tag
//   trust.trustpilot / trust.gridinsoft  {"url": "..."} only after the real entry exists; null = no badge
//   shop.liveApproved                  stays false until the owner approves live payments
// Every link in the output is relative, so the site works under any base path such as /VectolynCloud/.

import { readFileSync, writeFileSync, mkdirSync, cpSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pages, extraPages, routes, nav, footerLinks, strings, posts, team, icon } from './src/pages.mjs';
import { resolveHeads } from './src/heads.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const out = join(root, 'build', 'pages');
const config = JSON.parse(readFileSync(join(here, 'site.config.json'), 'utf8'));
const base = config.baseUrl.endsWith('/') ? config.baseUrl : config.baseUrl + '/';

// Newest release (pre-releases included) from the GitHub API, so the status box shows a real version.
// Offline or rate-limited builds simply leave it out.
config.latestRelease = null;
if (process.env.SITE_OFFLINE !== '1') {
  try {
    const repoPath = new URL(config.repo).pathname.replace(/^\/|\/$/g, '');
    const res = await fetch(`https://api.github.com/repos/${repoPath}/releases?per_page=1`, {
      headers: { accept: 'application/vnd.github+json', 'user-agent': 'vectolyn-site-build' }, signal: AbortSignal.timeout(5000),
    });
    const [r] = res.ok ? await res.json() : [];
    if (r && /^v[0-9A-Za-z.-]{1,40}$/.test(r.tag_name) && r.html_url.startsWith(config.repo + '/releases/')) {
      config.latestRelease = { tag: r.tag_name, date: r.published_at.slice(0, 10), url: r.html_url };
    }
  } catch { /* leave it out */ }
}
// Minecraft heads of the team, cropped from the active skins at build time (never loaded from a third party).
config.heads = await resolveHeads(team.filter((m) => m.mcName).map((m) => m.mcName), { siteDir: here, offline: process.env.SITE_OFFLINE === '1' });
for (const [n, h] of Object.entries(config.heads)) console.log(`team: head of ${n} (UUID ${h.uuid}) -> ${h.file}`);
const langs = ['de', 'en'];

export const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** Relative link from one output path ('de/shop/') to another ('en/' or 'brand/x.svg'). */
function rel(from, to) {
  const depth = from.split('/').filter(Boolean).length;
  const target = to === '' ? './' : to;
  return ('../'.repeat(depth) + target) || './';
}

/** x-default: the language choice for the home page, English for every other page. */
const xdefault = (key) => (key === 'home' ? '' : routes[key].en);

function jsonLd(data) {
  // A data block, not a script: CSP does not apply, and "<" is escaped so the block cannot end early.
  return `<script type="application/ld+json">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>`;
}

function csp() {
  const connect = ["'self'"];
  const script = ["'self'"];
  const img = ["'self'", 'data:'];
  if (config.testNetwork.statusUrl) connect.push(new URL(config.testNetwork.statusUrl).origin);
  if (config.analytics.gtmId) {
    script.push('https://www.googletagmanager.com');
    img.push('https://www.googletagmanager.com', 'https://*.google-analytics.com');
    connect.push('https://*.google-analytics.com', 'https://*.analytics.google.com', 'https://www.googletagmanager.com');
  }
  return [
    "default-src 'self'", `script-src ${script.join(' ')}`, "style-src 'self'", `img-src ${img.join(' ')}`,
    "font-src 'self'", `connect-src ${connect.join(' ')}`, "object-src 'none'", "base-uri 'self'", "form-action 'self'",
  ].join('; ');
}

const organization = {
  '@type': 'Organization', '@id': base + '#org', name: 'Vectolyn', url: base,
  logo: base + 'brand/png/mark-3d-512.png', sameAs: [config.repo],
  founder: { '@type': 'Person', name: 'Tim Hauke', url: 'https://github.com/VectoDE' },
};

function head(page, lang, path) {
  const s = strings[lang];
  const altKey = page.key || (path === '' && !page.noindex ? 'home' : null);
  const alt = altKey ? langs.map((l) => `<link rel="alternate" hreflang="${l}" href="${base}${routes[altKey][l]}">`).join('\n') : '';
  const xdef = page.key ? `<link rel="alternate" hreflang="x-default" href="${base}${xdefault(page.key)}">`
    : path === '' && !page.noindex ? `<link rel="alternate" hreflang="x-default" href="${base}">` : '';
  const url = base + path;
  const title = page.title;
  const graph = [
    organization,
    { '@type': 'WebSite', '@id': base + '#site', url: base, name: 'VectolynCloud', inLanguage: ['de', 'en'], publisher: { '@id': base + '#org' } },
    {
      '@type': page.type || 'WebPage', '@id': url + '#page', url, name: title, description: page.description, inLanguage: lang,
      isPartOf: { '@id': base + '#site' }, dateModified: config.updated, author: { '@id': base + '#org' }, publisher: { '@id': base + '#org' },
      ...(page.ld || {}),
    },
  ];
  if (page.crumbs) {
    graph.push({
      '@type': 'BreadcrumbList', itemListElement: page.crumbs.map((c, i) => ({
        '@type': 'ListItem', position: i + 1, name: c.name, item: base + routes[c.key][lang],
      })),
    });
  }
  if (page.extraLd) graph.push(...page.extraLd(base));
  const gtm = config.analytics.gtmId ? ` data-gtm="${esc(config.analytics.gtmId)}"` : '';
  return `<!doctype html>
<html lang="${lang}"${gtm}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="${csp()}">
<meta name="referrer" content="strict-origin-when-cross-origin">
<title>${esc(title)}</title>
<meta name="description" content="${esc(page.description)}">
<meta name="author" content="Tim Hauke (VectoDE)">
${page.noindex ? '<meta name="robots" content="noindex">' : '<meta name="robots" content="index,follow,max-image-preview:large">'}
<meta name="theme-color" content="#14111F" media="(prefers-color-scheme: dark)">
<meta name="theme-color" content="#F3F5F7" media="(prefers-color-scheme: light)">
<meta name="color-scheme" content="dark light">
${config.analytics.searchConsoleToken ? `<meta name="google-site-verification" content="${esc(config.analytics.searchConsoleToken)}">` : ''}
<link rel="canonical" href="${url}">
<link rel="alternate" type="application/atom+xml" title="${esc(s.feedTitle)}" href="${base}${lang}/blog/feed.xml">
${alt}
${xdef}
<link rel="icon" href="${rel(path, 'brand/mark-on-dark.svg')}" type="image/svg+xml">
<link rel="icon" href="${rel(path, 'brand/png/favicon-32.png')}" type="image/png" sizes="32x32">
<link rel="apple-touch-icon" href="${rel(path, 'brand/png/apple-touch-icon.png')}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="VectolynCloud">
<meta property="og:locale" content="${lang === 'de' ? 'de_DE' : 'en_US'}">
<meta property="og:locale:alternate" content="${lang === 'de' ? 'en_US' : 'de_DE'}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(page.description)}">
<meta property="og:url" content="${url}">
<meta property="og:image" content="${base}brand/png/social-preview.png">
<meta property="og:image:width" content="1280">
<meta property="og:image:height" content="640">
<meta property="og:image:alt" content="${esc(s.ogAlt)}">
<meta name="twitter:card" content="summary_large_image">
<link rel="preload" href="${rel(path, 'assets/fonts/pixelify-sans.woff2')}" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="${rel(path, 'assets/fonts/inter.woff2')}" as="font" type="font/woff2" crossorigin>
<script src="${rel(path, 'assets/theme.js')}"></script>
<link rel="stylesheet" href="${rel(path, 'assets/site.css')}">
<noscript><link rel="stylesheet" href="${rel(path, 'assets/nojs.css')}"></noscript>
<script src="${rel(path, 'assets/site.js')}" defer></script>
${jsonLd({ '@context': 'https://schema.org', '@graph': graph })}
</head>`;
}

function header(page, lang, path) {
  const s = strings[lang];
  const other = lang === 'de' ? 'en' : 'de';
  const isCurrent = (key) => page.key === key || page.section === key;
  const inGroup = (g) => g.items.some((i) => i.key === page.key || i.key === page.section);
  const link = (i, cls = '') => `<a class="${cls}" href="${rel(path, routes[i.key][lang])}"${isCurrent(i.key) ? ' aria-current="page"' : ''}>`;
  const latest = posts[0];
  const news = latest ? `<aside class="panel-news" aria-label="${esc(s.newsLabel)}"><p class="panel-intro">${esc(s.newsLabel)}</p><a class="news-card" href="${rel(path, routes['post-' + latest.slug.en][lang])}"><time datetime="${latest.date}">${esc(s.fmtDate(latest.date))}</time><span class="pl-title">${esc(latest.title[lang])}</span><span class="pl-desc">${esc(latest.summary[lang])}</span></a><a class="panel-all" href="${rel(path, routes.blog[lang])}">${esc(s.allNews)}</a></aside>` : '';
  const groups = nav.groups.map((g) => `<li class="nav-group${inGroup(g) ? ' in-section' : ''}">
      <button class="nav-trigger" type="button" aria-expanded="false" aria-controls="panel-${g.id}">${esc(g[lang])}<span class="caret" aria-hidden="true"></span></button>
      <div class="nav-panel" id="panel-${g.id}">
        <div class="panel-main">
          <p class="panel-intro">${esc(g.intro[lang])}</p>
          <ul>${g.items.map((i) => `<li>${link(i, 'panel-link')}<span class="pl-art" aria-hidden="true">${icon(i.art, 'pl-icon')}</span><span class="pl-text"><span class="pl-title">${esc(i[lang])}</span><span class="pl-desc">${esc(i.desc[lang])}</span></span></a></li>`).join('')}</ul>
        </div>
        ${news}
      </div>
    </li>`).join('');
  const flat = nav.flat.map((i) => `<li>${link(i, 'nav-link')}${esc(i[lang])}</a></li>`).join('');
  const switchTo = page.key ? rel(path, routes[page.key][other]) : rel(path, other + '/');
  return `<a class="skip" href="#main">${esc(s.skip)}</a>
<header class="site-head">
  <div class="progress" aria-hidden="true"><span></span></div>
  <div class="head-inner">
    <a class="brand" href="${rel(path, routes.home[lang])}"><img class="mark-dark" src="${rel(path, 'brand/mark-on-dark.svg')}" width="28" height="28" alt=""><img class="mark-light" src="${rel(path, 'brand/mark-on-light.svg')}" width="28" height="28" alt=""><span class="brand-name">VectolynCloud</span></a>
    <a class="lang lang-bar" href="${switchTo}" hreflang="${other}" lang="${other}"><span class="sr-only">${esc(s.switchHint)} </span>${esc(s.switchShort)}</a>
    <button class="menu-button" type="button" aria-expanded="false" aria-controls="site-nav"><span class="menu-icon" aria-hidden="true"></span><span class="menu-label">${esc(s.menu)}</span></button>
    <nav id="site-nav" class="site-nav" aria-label="${esc(s.mainNav)}">
      <ul class="nav-list">${groups}${flat}</ul>
      <div class="nav-end">
        <button class="theme-toggle" type="button" data-theme-toggle data-label-light="${esc(s.toLight)}" data-label-dark="${esc(s.toDark)}" hidden><span class="theme-icon" aria-hidden="true"></span><span class="theme-label">${esc(s.toLight)}</span></button>
        <a class="lang" href="${switchTo}" hreflang="${other}" lang="${other}"><span class="sr-only">${esc(s.switchHint)} </span>${esc(s.switchLabel)}</a>
        <a class="btn btn-primary btn-small" href="${rel(path, routes.download[lang])}">${esc(s.navCta)}</a>
      </div>
    </nav>
  </div>
</header>`;
}

function trustSlot(lang) {
  // Badges only for entries the owner has confirmed (site.config.json). Plain links: no third-party script.
  const s = strings[lang];
  const parts = [];
  if (config.trust.trustpilot?.url) parts.push(`<li><a class="trust" href="${esc(config.trust.trustpilot.url)}" rel="noopener">${esc(s.trustpilot)}</a></li>`);
  if (config.trust.gridinsoft?.url) parts.push(`<li><a class="trust" href="${esc(config.trust.gridinsoft.url)}" rel="noopener">${esc(s.gridinsoft)}</a></li>`);
  return parts.length ? `<div class="trust-row"><h2 class="foot-h">${esc(s.trustHeading)}</h2><ul>${parts.join('')}</ul></div>` : '';
}

function footer(page, lang, path) {
  const s = strings[lang];
  const cols = footerLinks.map((col) => `<div><h2 class="foot-h">${esc(col[lang])}</h2><ul>${col.links.map((l) => {
    const href = l.key ? rel(path, routes[l.key][lang]) : l.href;
    return `<li><a href="${esc(href)}">${esc(l[lang])}</a></li>`;
  }).join('')}${col.consent ? `<li><button class="linklike" type="button" data-motion-toggle hidden data-label-pause="${esc(s.motionPause)}" data-label-play="${esc(s.motionPlay)}">${esc(s.motionPause)}</button></li>` : ''}${col.consent && config.analytics.gtmId ? `<li><button class="linklike" type="button" data-consent-open>${esc(s.consentSettings)}</button></li>` : ''}</ul></div>`).join('');
  return `<footer class="site-foot">
  <div class="foot-brand">
    <a class="brand brand-foot" href="${rel(path, routes.home[lang])}"><img class="mark-dark" src="${rel(path, 'brand/mark-on-dark.svg')}" width="36" height="36" alt=""><img class="mark-light" src="${rel(path, 'brand/mark-on-light.svg')}" width="36" height="36" alt=""><span>VectolynCloud</span></a>
    <p>${esc(s.footClaim)}</p>
  </div>
  <div class="foot-cols">${cols}</div>
  <div class="social"><h2 class="foot-h">${esc(s.socialHeading)}</h2><ul>
    <li><a href="${config.repo}"><svg viewBox="0 0 16 16" width="20" height="20" aria-hidden="true" focusable="false"><path fill="currentColor" d="M8 0a8 8 0 0 0-2.5 15.6c.4 0 .5-.2.5-.4v-1.5c-2.2.5-2.7-1-2.7-1-.4-.9-.9-1.2-.9-1.2-.7-.5.1-.5.1-.5.8.1 1.2.8 1.2.8.7 1.3 1.9.9 2.3.7.1-.5.3-.9.5-1.1-1.8-.2-3.6-.9-3.6-4 0-.9.3-1.6.8-2.1-.1-.2-.4-1 .1-2.1 0 0 .7-.2 2.2.8a7.5 7.5 0 0 1 4 0c1.5-1 2.2-.8 2.2-.8.4 1.1.2 1.9.1 2.1.5.6.8 1.3.8 2.1 0 3.1-1.9 3.7-3.6 3.9.3.3.6.8.6 1.5v2.2c0 .2.1.5.6.4A8 8 0 0 0 8 0z"/></svg><span>GitHub</span></a></li>
    ${config.social?.discord ? `<li><a href="${esc(config.social.discord)}"><span>Discord</span></a></li>` : ''}
    ${config.social?.youtube ? `<li><a href="${esc(config.social.youtube)}"><span>YouTube</span></a></li>` : ''}
  </ul></div>
  ${trustSlot(lang)}
  <p class="foot-meta">${s.footMeta(config.updated, config.repo)}</p>
  <p class="foot-meta">${esc(s.mojang)}</p>
</footer>
${config.analytics.gtmId ? consentBanner(lang, path) : ''}`;
}

function consentBanner(lang, path) {
  const s = strings[lang];
  return `<div class="consent" data-consent hidden role="region" aria-label="${esc(s.consentTitle)}">
  <p><strong>${esc(s.consentTitle)}</strong> ${esc(s.consentText)} <a href="${rel(path, routes.privacy[lang])}">${esc(s.consentMore)}</a></p>
  <p class="consent-actions"><button class="btn" type="button" data-consent-choice="granted">${esc(s.consentYes)}</button> <button class="btn" type="button" data-consent-choice="denied">${esc(s.consentNo)}</button></p>
</div>`;
}

function crumbs(page, lang, path) {
  if (!page.crumbs || page.crumbs.length < 2) return '';
  const last = page.crumbs.length - 1;
  return `<nav class="crumbs" aria-label="${esc(strings[lang].crumbs)}"><ol>${page.crumbs.map((c, i) => i === last
    ? `<li><span aria-current="page">${esc(c.name)}</span></li>`
    : `<li><a href="${rel(path, routes[c.key][lang])}">${esc(c.name)}</a></li>`).join('')}</ol></nav>`;
}

function render(page, lang, path) {
  const ctx = { lang, path, config, link: (key) => rel(path, routes[key][lang]), asset: (p) => rel(path, p), esc };
  return `${head(page, lang, path)}
<body>
${header(page, lang, path)}
<main id="main" tabindex="-1">
${crumbs(page, lang, path)}
${page.body(ctx)}
</main>
${footer(page, lang, path)}
</body>
</html>
`;
}

// Tables become one card per row on small screens. Each cell gets the column name as data-label, and the
// table roles are set explicitly so screen readers keep the table when CSS changes the display type.
function cardTables(html) {
  return html.replace(/<table>([\s\S]*?)<\/table>/g, (m, inner) => {
    const heads = [...inner.matchAll(/<th[^>]*>([\s\S]*?)<\/th>/g)].map((x) => x[1].replace(/<[^>]+>/g, '').replace(/"/g, '&quot;'));
    const body = inner
      .replace(/<thead>/g, '<thead role="rowgroup">').replace(/<tbody>/g, '<tbody role="rowgroup">')
      .replace(/<tr>/g, '<tr role="row">').replace(/<th scope="col">/g, '<th scope="col" role="columnheader">')
      .replace(/<tr role="row">([\s\S]*?)<\/tr>/g, (row, cells) => {
        let i = 0;
        return '<tr role="row">' + cells.replace(/<td>/g, () => `<td role="cell" data-label="${heads[i++] || ''}">`) + '</tr>';
      });
    return `<table role="table" class="card-table">${body}</table>`;
  });
}

function write(path, html) {
  html = cardTables(html);
  const file = join(out, path, path.endsWith('/') || path === '' ? 'index.html' : '');
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, html.replace(/\n{3,}/g, '\n\n').replace(/^\s*\n/gm, ''));
}

mkdirSync(out, { recursive: true });
cpSync(join(here, 'assets'), join(out, 'assets'), { recursive: true });
// One rule per team head; the CSP allows no inline styles, so the skin goes into the stylesheet.
const skinCss = Object.values(config.heads).map((h) => `.skin-${h.key} .f{background-image:url(${h.file.replace(/^assets\//, '')})}`).join('\n');
if (skinCss) writeFileSync(join(out, 'assets', 'site.css'), readFileSync(join(out, 'assets', 'site.css'), 'utf8') + '\n' + skinCss + '\n');

const sitemap = [];
for (const [key, build] of Object.entries(pages)) {
  for (const lang of langs) {
    const page = build(lang, config);
    const path = routes[key][lang];
    write(path, render({ ...page, key }, lang, path));
    if (!page.noindex) sitemap.push({ key });
  }
}

// Language choice at the root (x-default). No automatic redirect: the visitor picks.
write('', render({ ...extraPages.root('en', config), key: null }, 'en', ''));

// 404 page: GitHub Pages serves /404.html for unknown paths, at the site root, so links must be absolute.
const notFound = extraPages.notFound('en', config);
writeFileSync(join(out, '404.html'), render({ ...notFound, key: null }, 'en', '').replace(/(href|src)="(?!https?:|#)([^"]*)"/g, (m, a, v) => `${a}="${base}${v.replace(/^\.\//, '')}"`));

// sitemap.xml with the language alternates of every page
const seen = new Set();
const urls = [];
for (const { key } of sitemap) {
  if (seen.has(key) || key === 'notFound') continue;
  seen.add(key);
  for (const lang of langs) {
    const alts = langs.map((l) => `<xhtml:link rel="alternate" hreflang="${l}" href="${base}${routes[key][l]}"/>`).join('')
      + `<xhtml:link rel="alternate" hreflang="x-default" href="${base}${xdefault(key)}"/>`;
    urls.push(`<url><loc>${base}${routes[key][lang]}</loc><lastmod>${config.updated}</lastmod>${alts}</url>`);
  }
}
urls.unshift(`<url><loc>${base}</loc><lastmod>${config.updated}</lastmod>${langs.map((l) => `<xhtml:link rel="alternate" hreflang="${l}" href="${base}${routes.home[l]}"/>`).join('')}<xhtml:link rel="alternate" hreflang="x-default" href="${base}"/></url>`);
writeFileSync(join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${urls.join('\n')}
</urlset>
`);

// robots.txt: everything public, AI crawlers included. Takes effect only at a domain root (see site/README.md).
writeFileSync(join(out, 'robots.txt'), `# VectolynCloud website. Everything here is public, search engines and AI crawlers are welcome.
User-agent: *
Allow: /

Sitemap: ${base}sitemap.xml
`);

writeFileSync(join(out, 'llms.txt'), strings.llms(base, routes, config));

// Atom feed per language for the blog
for (const lang of langs) {
  const entries = posts.map((p) => `<entry><title>${esc(p.title[lang])}</title><link href="${base}${routes['post-' + p.slug.en][lang]}"/><id>${base}${routes['post-' + p.slug.en][lang]}</id><updated>${p.date}T12:00:00Z</updated><author><name>${esc(p.author)}</name></author><summary>${esc(p.summary[lang])}</summary></entry>`).join('\n');
  mkdirSync(join(out, lang, 'blog'), { recursive: true });
  writeFileSync(join(out, lang, 'blog', 'feed.xml'), `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="${lang}">
<title>${lang === 'de' ? 'VectolynCloud: Neuigkeiten' : 'VectolynCloud news'}</title>
<link href="${base}${routes.blog[lang]}"/><link rel="self" href="${base}${lang}/blog/feed.xml"/>
<id>${base}${routes.blog[lang]}</id><updated>${posts[0].date}T12:00:00Z</updated>
${entries}
</feed>
`);
}
writeFileSync(join(out, '.nojekyll'), '');
console.log(`Website written to ${out}`);
