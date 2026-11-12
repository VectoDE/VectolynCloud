// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Content of the website in German and English. Each page is a function (lang, config) that returns
// title, description, structured data and a body(ctx). Facts on these pages come from the repository
// (README, docs/cloud/*, docs/legal/*); nothing here is a number or promise that the repository does not back.

import { posts } from './posts.mjs';

const REPO = 'https://github.com/VectoDE/VectolynCloud';
const WIKI = REPO + '/wiki';
const BLOB = REPO + '/blob/main';

export const routes = {
  home: { de: 'de/', en: 'en/' },
  download: { de: 'de/download/', en: 'en/download/' },
  docs: { de: 'de/dokumentation/', en: 'en/documentation/' },
  websites: { de: 'de/dokumentation/website/', en: 'en/documentation/website/' },
  network: { de: 'de/testnetzwerk/', en: 'en/test-network/' },
  rules: { de: 'de/testnetzwerk/regeln/', en: 'en/test-network/rules/' },
  shop: { de: 'de/shop/', en: 'en/shop/' },
  team: { de: 'de/team/', en: 'en/team/' },
  support: { de: 'de/support/', en: 'en/support/' },
  faq: { de: 'de/faq/', en: 'en/faq/' },
  blog: { de: 'de/blog/', en: 'en/blog/' },
  imprint: { de: 'de/impressum/', en: 'en/imprint/' },
  privacy: { de: 'de/datenschutz/', en: 'en/privacy/' },
};

export const nav = {
  groups: [
    { id: 'product', de: 'VectolynCloud', en: 'VectolynCloud',
      intro: { de: 'Die Software für dein eigenes Netzwerk', en: 'The software for your own network' },
      items: [
        { key: 'home', art: 'window', de: 'Überblick', en: 'Overview', desc: { de: 'Was die Cloud macht und wo das Projekt steht', en: 'What the cloud does and where the project stands' } },
        { key: 'download', art: 'download', de: 'Download', en: 'Download', desc: { de: 'Releases, Voraussetzungen, Signaturen', en: 'Releases, requirements, signatures' } },
        { key: 'docs', art: 'book', de: 'Dokumentation', en: 'Documentation', desc: { de: 'Betrieb, Setup, Konsole, API', en: 'Operations, setup, console, API' } },
        { key: 'websites', art: 'setup', de: 'Website für dein Netzwerk', en: 'A website for your network', desc: { de: 'Drei geplante Wege', en: 'Three planned ways' } },
      ] },
    { id: 'network', de: 'Testnetzwerk', en: 'Test network',
      intro: { de: 'Vectolyn Network: VectolynCloud zum Anfassen', en: 'Vectolyn Network: try VectolynCloud in game' },
      items: [
        { key: 'network', art: 'crossplay', de: 'So spielst du mit', en: 'How to join', desc: { de: 'Status, Adresse, Java und Bedrock', en: 'Status, address, Java and Bedrock' } },
        { key: 'rules', art: 'rules', de: 'Regeln', en: 'Rules', desc: { de: 'Was gilt und was bei Verstößen passiert', en: 'What applies and what happens on a breach' } },
        { key: 'shop', art: 'crown', de: 'Shop', en: 'Shop', desc: { de: 'Kosmetisch, im Testmodus', en: 'Cosmetic, in test mode' } },
        { key: 'team', art: 'head', de: 'Team', en: 'Team', desc: { de: 'Wer dahintersteht', en: 'Who is behind it' } },
      ] },
  ],
  flat: [
    { key: 'blog', de: 'Neuigkeiten', en: 'News' },
    { key: 'support', de: 'Support', en: 'Support' },
    { key: 'faq', de: 'FAQ', en: 'FAQ' },
  ],
};

export const footerLinks = [
  { de: 'VectolynCloud', en: 'VectolynCloud', links: [
    { key: 'download', de: 'Download', en: 'Download' },
    { key: 'docs', de: 'Dokumentation', en: 'Documentation' },
    { key: 'websites', de: 'Website für dein Netzwerk', en: 'A website for your network' },
    { key: 'blog', de: 'Neuigkeiten', en: 'News' },
    { href: REPO + '/releases', de: 'Releases', en: 'Releases' },
  ] },
  { de: 'Testnetzwerk', en: 'Test network', links: [
    { key: 'network', de: 'So spielst du mit', en: 'How to join' },
    { key: 'rules', de: 'Regeln', en: 'Rules' },
    { key: 'shop', de: 'Shop', en: 'Shop' },
    { key: 'team', de: 'Team', en: 'Team' },
  ] },
  { de: 'Hilfe', en: 'Help', links: [
    { key: 'support', de: 'Support', en: 'Support' },
    { key: 'faq', de: 'Häufige Fragen', en: 'FAQ' },
    { href: REPO + '/discussions', de: 'Diskussionen', en: 'Discussions' },
    { href: REPO + '/security/policy', de: 'Sicherheitslücke melden', en: 'Report a vulnerability' },
  ] },
  { de: 'Rechtliches', en: 'Legal', consent: true, links: [
    { key: 'imprint', de: 'Impressum', en: 'Imprint' },
    { key: 'privacy', de: 'Datenschutz', en: 'Privacy' },
    { href: REPO + '/blob/main/LICENSE', de: 'Lizenz GPL-3.0', en: 'License GPL-3.0' },
  ] },
];

export const strings = {
  de: {
    skip: 'Zum Inhalt springen', newsLabel: 'Neuigkeiten', footClaim: 'Freie Software für dein eigenes Minecraft-Netzwerk. Gebaut in Itzehoe, offen auf GitHub.', allNews: 'Alle Neuigkeiten', fmtDate: (d) => dateDe(d), menu: 'Menü', switchHint: 'Sprache wechseln:', switchShort: 'EN', toLight: 'Helles Design', toDark: 'Dunkles Design', navCta: 'Herunterladen', mainNav: 'Hauptnavigation', crumbs: 'Brotkrumen', switchLabel: 'English',
    ogAlt: 'Wortmarke VectolynCloud mit dem Pixel-Zeichen',
    trustHeading: 'Bewertungen und Prüfungen', socialHeading: 'Folgen', feedTitle: 'VectolynCloud: Neuigkeiten', trustpilot: 'Bewertungen auf Trustpilot', gridinsoft: 'Website-Prüfung bei Gridinsoft',
    footMeta: (d, repo) => `Herausgegeben von Tim Hauke (VectoDE). Stand: ${dateDe(d)}. Diese Website ist Teil des <a href="${repo}/tree/main/site">Quelltexts</a> und steht wie alles im Projekt unter GPL-3.0.`,
    mojang: 'Minecraft ist eine Marke von Mojang Synergies AB. Vectolyn steht in keiner Verbindung zu Mojang oder Microsoft.',
    consentTitle: 'Statistik', consentText: 'Wir würden gern mit Google Analytics zählen, welche Seiten gelesen werden. Das passiert nur, wenn du zustimmst; ablehnen ist genauso einfach.',
    consentMore: 'Mehr dazu', motionPause: 'Animationen anhalten', motionPlay: 'Animationen abspielen', consentYes: 'Zustimmen', consentNo: 'Ablehnen', consentSettings: 'Statistik-Einstellungen',
  },
  en: {
    skip: 'Skip to content', newsLabel: 'News', footClaim: 'Free software for your own Minecraft network. Built in Itzehoe, open on GitHub.', allNews: 'All news', fmtDate: (d) => dateEn(d), menu: 'Menu', switchHint: 'Switch language:', switchShort: 'DE', toLight: 'Light mode', toDark: 'Dark mode', navCta: 'Download', mainNav: 'Main navigation', crumbs: 'Breadcrumb', switchLabel: 'Deutsch',
    ogAlt: 'The VectolynCloud wordmark with the pixel mark',
    trustHeading: 'Reviews and checks', socialHeading: 'Follow', feedTitle: 'VectolynCloud news', trustpilot: 'Reviews on Trustpilot', gridinsoft: 'Website check by Gridinsoft',
    footMeta: (d, repo) => `Published by Tim Hauke (VectoDE). Last updated ${dateEn(d)}. This website is part of the <a href="${repo}/tree/main/site">source code</a> and, like the rest of the project, licensed under GPL-3.0.`,
    mojang: 'Minecraft is a trademark of Mojang Synergies AB. Vectolyn is not affiliated with Mojang or Microsoft.',
    consentTitle: 'Statistics', consentText: 'We would like to count which pages get read, with Google Analytics. That only happens if you agree; declining is just as easy.',
    consentMore: 'Details', motionPause: 'Pause animations', motionPlay: 'Play animations', consentYes: 'Agree', consentNo: 'Decline', consentSettings: 'Statistics settings',
  },
  llms: (base, r, config) => `# VectolynCloud

> VectolynCloud is open-source software (GPL-3.0-or-later) for running your own Minecraft network: one Java program that downloads and verifies the server software, sets up the database and starts proxy, lobby, game servers and minigames. Java and Bedrock players join the same network. Published by Tim Hauke (VectoDE). The project is in stage 2 of 8; releases so far are signed pre-releases for trying out, not for production.

Vectolyn Network is a planned public test network where players can try a network run by VectolynCloud. It is not online yet; its address will be published on the test network page.

## Pages (English)
- [Home](${base}${r.home.en}): what VectolynCloud does and the current project status
- [Download](${base}${r.download.en}): release files, requirements, verifying signatures
- [Documentation](${base}${r.docs.en}): guides for operators and developers
- [A website for your network](${base}${r.websites.en}): three planned ways to build a network website
- [Test network](${base}${r.network.en}): how to join Vectolyn Network once it is online
- [Rules](${base}${r.rules.en}): rules of the test network
- [Shop](${base}${r.shop.en}): cosmetic shop of the test network, test mode only, no purchases possible
- [FAQ](${base}${r.faq.en})

## Seiten (Deutsch)
- [Start](${base}${r.home.de})
- [Download](${base}${r.download.de})
- [Dokumentation](${base}${r.docs.de})
- [Testnetzwerk](${base}${r.network.de})
- [FAQ](${base}${r.faq.de})

## Sources
- [Repository](${config.repo}): code, README, issues
- [Technical documentation](${config.repo}/tree/main/docs)
- [Wiki](${config.repo}/wiki)
- [Releases](${config.repo}/releases)
`,
};

function dateDe(d) { const [y, m, day] = d.split('-').map(Number); return `${day}. ${['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'][m - 1]} ${y}`; }
function dateEn(d) { const [y, m, day] = d.split('-').map(Number); return `${['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'][m - 1]} ${day}, ${y}`; }

const L = (lang) => (de, en) => (lang === 'de' ? de : en);
const ext = (href, text) => `<a href="${href}">${text}</a>`;

const ICONS = {
  crossplay: 'M1 2h6v6H1zM9 8h6v6H9zM7 4h3v2H7zM10 4h2v4h-2zM4 8h2v4H4zM6 10h3v2H6z',
  check: 'M1 7h3v3h3v3H4v-3H1zM7 10h3V7h3V4h3v3h-3v3h-3v3H7z',
  setup: 'M1 2h4v4H1zM7 3h8v2H7zM1 7h4v4H1zM7 8h8v2H7zM1 12h4v3H1zM7 12h6v2H7z',
  scale: 'M1 11h4v4H1zM6 7h4v8H6zM11 2h4v13h-4z',
  window: 'M1 2h14v12H1zM3 6v6h10V6z',
  key: 'M1 4h7v7H1zM3 6v3h3V6zM8 6h7v3H8zM11 9h2v3h-2zM14 9h1v3h-1z',
  download: 'M7 1h2v7h2V6h2v2h-2v2H9v2H7v-2H5V8H3V6h2v2h2zM1 13h14v2H1z',
  book: 'M1 2h6v1h2V2h6v12H9v1H7v-1H1zM3 4v8h4V4zM9 4v8h4V4z',
  rules: 'M3 1h10v14H3zM5 4h6v1H5zM5 7h6v1H5zM5 10h4v1H5z',
  head: 'M3 1h10v10H3zM5 5h2v2H5zM9 5h2v2H9zM6 8h4v1H6zM4 12h8v3H4z',
  crown: 'M1 4h2v3h2V4h2v3h2V4h2v3h2V4h2v9H1zM3 10v1h10v-1z',
  news: 'M1 2h12v12H1zM13 5h2v9h-2zM3 4h8v3H3zM3 9h8v1H3zM3 11h5v1H3z',
  status: 'M1 11h3v4H1zM5 8h3v7H5zM9 5h3v10H9zM13 1h2v14h-2z',
};
const SHOP_ART = {
  crown: 'M1 4h2v3h2V4h2v3h2V4h2v3h2V4h2v9H1zM3 10v1h10v-1z',
  sparkle: 'M7 1h2v4H7zM7 11h2v4H7zM1 7h4v2H1zM11 7h4v2h-4zM5 5h6v6H5zM7 7v2h2V7z',
  hourglass: 'M3 1h10v2h-1v3h-1v1h-1v2h1v1h1v3h1v2H3v-2h1v-3h1V9h1V7H5V6H4V3H3zM6 12v1h4v-1z',
  picture: 'M1 2h14v12H1zM3 4v8h10V4zM10 5h2v2h-2zM4 11V9h2V7h2v2h1v1h2v1z',
};
export const icon = (name, cls = 'icon') => `<svg class="${cls}" viewBox="0 0 16 16" width="32" height="32" aria-hidden="true" focusable="false" shape-rendering="crispEdges"><path fill-rule="evenodd" d="${ICONS[name]}"/></svg>`;


/** Hero picture: a voxel island. Static AVIF/WebP for everyone; scene.js swaps in the live 3D view on capable devices. */
function worldVisual(t, ctx) {
  return `<figure class="world" data-scene>
  <div class="world-stage">
    <picture><source type="image/avif" srcset="${ctx.asset('assets/img/world.avif')}"><img src="${ctx.asset('assets/img/world.webp')}" width="960" height="760" decoding="async" fetchpriority="low" alt="${t('Eine kleine Voxel-Insel mit Gras, Sand, Wasser und Bäumen. In der Mitte steht das Vectolyn-Zeichen aus türkisen und goldenen Blöcken.', 'A small voxel island with grass, sand, water and trees. The Vectolyn mark stands in the middle, built from cyan and gold blocks.')}"></picture>
  </div>
  <figcaption>${t('Vorschau im Vectolyn-Stil, keine Aufnahme aus dem Testnetzwerk.', 'A preview in the Vectolyn style, not a capture from the test network.')}</figcaption>
  <button class="motion-toggle" type="button" data-motion-toggle data-needs-3d hidden data-label-pause="${t('Animation anhalten', 'Pause animation')}" data-label-play="${t('Animation abspielen', 'Play animation')}">${t('Animation anhalten', 'Pause animation')}</button>
</figure>`;
}

/** How players reach the servers. A static SVG; scene.js may add a 3D version on capable devices. */
function netVisual(t) {
  const box = (x, y, w, h, label, cls) => `<g class="${cls}"><rect x="${x}" y="${y}" width="${w}" height="${h}"/><text x="${x + w / 2}" y="${y + h / 2 + 6}" text-anchor="middle">${label}</text></g>`;
  const servers = ['Lobby', 'Survival', 'Creative', t('Minispiele', 'Minigames')];
  return `<figure class="net">
  <div class="net-stage">
  <svg class="net-svg" viewBox="0 0 480 380" role="img" aria-labelledby="net-title" shape-rendering="crispEdges">
    <title id="net-title">${t('Java- und Bedrock-Spieler verbinden sich mit dem Proxy, der sie auf Lobby, Survival, Creative und Minispiele verteilt. VectolynCloud startet, skaliert und überwacht alle Server.', 'Java and Bedrock players connect to the proxy, which sends them on to lobby, survival, creative and minigames. VectolynCloud starts, scales and watches every server.')}</title>
    <g class="wires">
      <path d="M126 60V100M354 60V100M240 148V176M60 176H420M60 176V204M180 176V204M300 176V204M420 176V204"/>
      <path class="ctl" d="M60 248V296M180 248V296M300 248V296M420 248V296"/>
    </g>
    ${box(56, 16, 140, 44, 'Java', 'n-java')}
    ${box(284, 16, 140, 44, 'Bedrock', 'n-bedrock')}
    ${box(96, 100, 288, 48, 'Proxy (Velocity)', 'n-proxy')}
    ${servers.map((name, i) => box(8 + i * 120, 204, 104, 44, name, 'n-server')).join('')}
    <g class="n-cloud"><rect x="8" y="296" width="464" height="68"/><text x="240" y="326" text-anchor="middle">VectolynCloud</text><text class="sub" x="240" y="350" text-anchor="middle">${t('startet, skaliert, überwacht', 'starts, scales, watches')}</text></g>
    <g class="packets"><rect class="pk pk1" x="122" y="60" width="8" height="8"/><rect class="pk pk2" x="350" y="60" width="8" height="8"/><rect class="pk pk3" x="236" y="148" width="8" height="8"/><rect class="pk pk4" x="56" y="172" width="8" height="8"/></g>
  </svg>
  </div>
  <button class="motion-toggle" type="button" data-motion-toggle hidden data-label-pause="${t('Animation anhalten', 'Pause animation')}" data-label-play="${t('Animation abspielen', 'Play animation')}">${t('Animation anhalten', 'Pause animation')}</button>
</figure>`;
}


/** A pixel head as a CSS cube. Purely decorative; open roles get an empty wireframe. */
// Team head: the real Minecraft head (cropped at build time, see heads.mjs) or the open placeholder cube.
const head3d = (head, label, size = '') => head
  ? `<div class="head3d head-skin skin-${head} ${size}" role="img" aria-label="${label}"><div class="cube" aria-hidden="true"><span class="f f-front"></span><span class="f f-back"></span><span class="f f-right"></span><span class="f f-left"></span><span class="f f-top"></span><span class="f f-bottom"></span></div></div>`
  : `<div class="head3d head-open" role="img" aria-label="${label}"><div class="cube" aria-hidden="true"><span class="f f-front"></span><span class="f f-back"></span><span class="f f-right"></span><span class="f f-left"></span><span class="f f-top"></span><span class="f f-bottom"></span></div></div>`;

/** Team roster. mcName is the Java Edition name whose active skin the build fetches; leave it out for open roles. */
export const team = [
  { role: { de: 'Leitung und Entwicklung', en: 'Lead and development' }, what: { de: 'Plant das Projekt, schreibt den Code, entscheidet über Releases.', en: 'Plans the project, writes the code, decides on releases.' }, who: 'Tim Hauke (<a href="https://github.com/VectoDE">VectoDE</a>)', person: 'Tim Hauke', mcName: 'VectoDE' },
  { role: { de: 'Administration', en: 'Administration' }, what: { de: 'Betreut die Server des Testnetzwerks.', en: 'Looks after the servers of the test network.' } },
  { role: { de: 'Moderation', en: 'Moderation' }, what: { de: 'Achtet auf die Regeln im Spiel und im Discord.', en: 'Keeps an eye on the rules in game and in Discord.' } },
  { role: { de: 'Support', en: 'Support' }, what: { de: 'Hilft bei Fragen zu Konto, Bestellungen und Strafen.', en: 'Helps with questions about accounts, orders and penalties.' } },
  { role: { de: 'Bau', en: 'Building' }, what: { de: 'Baut Lobby und Spielwelten.', en: 'Builds the lobby and game worlds.' } },
];

function pageHead(kicker, h1, lede) {
  return `<section class="page-head">
  ${kicker ? `<p class="audience">${kicker}</p>` : ''}
  <h1>${h1}</h1>
  ${lede ? `<p class="lede">${lede}</p>` : ''}
</section>`;
}

/** Status of the test network. Without a status URL it says plainly that nothing is online. */
function statusCard(t, ctx) {
  const tn = ctx.config.testNetwork;
  const live = tn.statusUrl ? ` data-status-url="${ctx.esc(tn.statusUrl)}"` : '';
  return `<div class="status-card"${live}>
  <p class="status-line"><span class="dot ${tn.statusUrl ? 'dot-loading' : 'dot-off'}" data-status-dot aria-hidden="true"></span><span data-status-text>${tn.statusUrl
    ? t('Status wird abgefragt …', 'Checking status …')
    : t('Noch nicht online', 'Not online yet')}</span></p>
  <p class="status-sub" data-status-sub>${t('Vectolyn Network, das Testnetzwerk', 'Vectolyn Network, the test network')}</p>
  <dl class="status-facts">
    <div><dt>${t('Spieler', 'Players')}</dt><dd data-status-players>–</dd></div>
    <div><dt>${t('Uptime, 30 Tage', 'Uptime, 30 days')}</dt><dd data-status-uptime>–</dd></div>
    <div><dt>${t('Version', 'Version')}</dt><dd data-status-version>–</dd></div>
    <div><dt>${t('Patch-Stand', 'Patch level')}</dt><dd data-status-patch>–</dd></div>
  </dl>
  ${tn.statusUrl ? '' : `<p class="status-empty">${t('Werte erscheinen, sobald das Testnetzwerk läuft.', 'Values appear once the test network runs.')}</p>`}
  ${address(t, ctx)}
  ${ctx.config.latestRelease ? `<p class="status-release">${t('Neueste VectolynCloud-Version:', 'Latest VectolynCloud version:')} <a href="${ctx.esc(ctx.config.latestRelease.url)}">${ctx.esc(ctx.config.latestRelease.tag)}</a> (${ctx.esc(ctx.config.latestRelease.date)})</p>` : ''}
</div>`;
}

function address(t, ctx) {
  const tn = ctx.config.testNetwork;
  if (!tn.address) {
    return `<p class="address address-pending"><span class="label">${t('Adresse', 'Address')}</span> <span>${t('folgt mit der eigenen Domain', 'follows with our own domain')}</span></p>`;
  }
  return `<p class="address"><span class="label">${t('Adresse', 'Address')}</span> <code>${ctx.esc(tn.address)}</code>
  <button class="btn btn-small" type="button" data-copy="${ctx.esc(tn.address)}" data-copied="${t('Kopiert', 'Copied')}">${t('Kopieren', 'Copy')}</button></p>
  <p class="sr-only" aria-live="polite" data-copy-status></p>`;
}

const stages = (t) => [
  [t('Fundament: Repository, Build, Konfiguration', 'Foundation: repository, build, configuration'), 'done'],
  [t('VectolynCloud-Kern', 'VectolynCloud core'), 'now'],
  [t('Vectolyn-Plugin', 'Vectolyn plugin'), ''],
  [t('Lobby', 'Lobby'), ''],
  [t('Survival, Creative, Mod-Server', 'Survival, Creative, mod server'), ''],
  [t('Minispiele und Anti-Cheat', 'Minigames and anti-cheat'), ''],
  [t('Marke und Ressourcenpaket', 'Brand and resource pack'), ''],
  [t('Dashboard, Discord-Bot, Website, Shop', 'Dashboard, Discord bot, website, shop'), ''],
];

function roadmap(t) {
  const label = { done: t('Fertig', 'Done'), now: t('In Arbeit', 'In progress'), '': t('Geplant', 'Planned') };
  return `<ol class="stages">${stages(t).map(([name, state], i) => `<li class="${state}"><span class="slot" aria-hidden="true">${i + 1}</span><span class="name">${name}</span><span class="state">${label[state]}</span></li>`).join('')}</ol>`;
}

const draftNote = (t) => `<p class="notice notice-warn"><strong>${t('Entwurf.', 'Draft.')}</strong> ${t('Dieser Text ist noch nicht anwaltlich geprüft. Er wird ersetzt, sobald die Prüfung abgeschlossen ist.', 'This text has not been reviewed by a lawyer yet. It will be replaced once the review is done.')}</p>`;

// ---------- Download page: one-click download for the visitor's system, all files below ----------
// Everything comes from config.release (src/release.mjs, read at build time). Links point at GitHub's
// release download URLs; nothing is mirrored. A file missing from the release becomes a notice.
const fmtSize = (lang, bytes) => `${(bytes / 1048576).toFixed(1).replace('.', lang === 'de' ? ',' : '.')} MB`;

function downloadPicker(t, ctx, rel, lang) {
  const verify = `#${t('pruefen', 'verify')}`;
  const verifyGuide = `${BLOB}/docs/cloud/operations.md#verifying-a-release`;
  const date = rel.date ? (lang === 'de' ? dateDe(rel.date) : dateEn(rel.date)) : '';
  const systems = [
    ['windows', 'Windows', rel.files.windows],
    ['macos', 'macOS', rel.files.unix],
    ['linux', 'Linux', rel.files.unix],
  ];
  const hash = (f) => f.sha256
    ? `<div class="dl-hash"><span class="label">SHA-256</span><code>${ctx.esc(f.sha256)}</code><button class="btn btn-small" type="button" data-copy="${ctx.esc(f.sha256)}" data-copied="${t('Kopiert', 'Copied')}">${t('Prüfsumme kopieren', 'Copy checksum')}</button></div>`
    : `<p class="notice notice-warn">${t('Für diese Datei steht keine Prüfsumme in SHA256SUMS. Prüfe sie über die Signatur, bevor du sie startest.', 'This file has no checksum in SHA256SUMS. Check its signature before you run it.')}</p>`;
  const pick = ([os, label, f], i) => `<div class="dl-pick" data-os="${os}"${i ? ' hidden' : ''}>
    <h2 id="dl-h-${os}">${t(`VectolynCloud für ${label}`, `VectolynCloud for ${label}`)}</h2>
    <p class="dl-auto" hidden>${t('Passend zu deinem System ausgewählt.', 'Picked to match your system.')}</p>
    ${f ? `<p class="actions"><a class="btn btn-primary btn-large" href="${ctx.esc(f.url)}" aria-describedby="dl-file-${os}">${icon('download', 'btn-icon')}${t('Herunterladen', 'Download')}</a></p>
    <p class="dl-file" id="dl-file-${os}"><code>${ctx.esc(f.name)}</code>, ${fmtSize(lang, f.size)}</p>
    ${hash(f)}
    <p class="dl-verify">${icon('key', 'icon-inline')}<a href="${verify}">${t('Signatur prüfen', 'Verify the signature')}</a> ${t('mit cosign und SLSA', 'with cosign and SLSA')} (${ext(verifyGuide, t('Anleitung', 'guide'))})</p>`
    : `<p class="notice notice-warn">${t(`Im Release ${ctx.esc(rel.tag)} fehlt die Datei für ${label}. Nimm eine Datei aus der Liste unten oder schau auf der`, `Release ${ctx.esc(rel.tag)} has no file for ${label}. Pick one from the list below or look at the`)} <a href="${ctx.esc(rel.url)}">${t('Release-Seite', 'release page')}</a>.</p>`}
  </div>`;
  const rows = [
    ['windows', t('Windows: Programm, start.bat und start.ps1', 'Windows: program, start.bat and start.ps1')],
    ['unix', t('Linux und macOS: Programm, start.sh, systemd-Unit und install.sh', 'Linux and macOS: program, start.sh, systemd unit and install.sh')],
    ['jar', t('nur das Programm, für java -jar, Docker und Pterodactyl', 'the program alone, for java -jar, Docker and Pterodactyl')],
    ['templates', t('Vorlagen für Server und Gruppen', 'templates for servers and groups')],
    ['setup', t('Beispiel für die Setup-Datei (Start ohne Terminal)', 'example setup file (start without a terminal)')],
  ];
  return `<section class="band dl-band">
  <div class="dl-card card" data-dl>
    <p class="dl-version"><span class="kicker">${t('Version', 'Version')} <span class="dl-num">${ctx.esc(rel.version)}</span></span>${date ? ` <time datetime="${ctx.esc(rel.date)}">${date}</time>` : ''}${rel.prerelease ? ` <span class="badge">${t('Vorabversion', 'Pre-release')}</span>` : ''}</p>
    ${systems.map(pick).join('\n    ')}
    <p class="dl-other">${t('Anderes System oder nur das Programm? Alle Dateien stehen in der Liste darunter.', 'Another system or just the program? Every file is in the list below.')}</p>
  </div>
  <h2 id="dl-all">${t('Alle Dateien von', 'All files of')} <span class="dl-num">${ctx.esc(rel.tag)}</span></h2>
  <div class="table-wrap dl-table"><table>
    <thead><tr><th scope="col">${t('Datei', 'File')}</th><th scope="col">${t('Für', 'For')}</th><th scope="col">${t('Größe', 'Size')}</th><th scope="col">SHA-256</th></tr></thead>
    <tbody>
${rows.map(([k, what]) => { const f = rel.files[k]; return f
    ? `      <tr><td><a href="${ctx.esc(f.url)}"><code>${ctx.esc(f.name)}</code></a></td><td>${what}</td><td>${fmtSize(lang, f.size)}</td><td><code class="hash">${f.sha256 ? ctx.esc(f.sha256) : t('nicht in SHA256SUMS', 'not in SHA256SUMS')}</code></td></tr>`
    : `      <tr><td>${t('fehlt in diesem Release', 'missing from this release')}</td><td>${what}</td><td>–</td><td>–</td></tr>`; }).join('\n')}
    </tbody>
  </table></div>
  <p>${rel.sumsUrl ? `${ext(ctx.esc(rel.sumsUrl), 'SHA256SUMS')}, ` : ''}${rel.provenanceUrl ? `${ext(ctx.esc(rel.provenanceUrl), t('SLSA-Herkunftsbescheinigung', 'SLSA provenance'))}, ` : ''}${t('Signaturen (<code>*.sigstore.json</code>) und Release-Notizen auf der', 'signatures (<code>*.sigstore.json</code>) and release notes on the')} ${ext(ctx.esc(rel.url), t('Release-Seite', 'release page'))}. ${t('Ältere Versionen:', 'Older versions:')} ${ext(REPO + '/releases', t('alle Releases', 'all releases'))}.</p>
</section>`;
}

export const pages = {
  home(lang) {
    const t = L(lang);
    return {
      title: t('VectolynCloud: dein eigenes Minecraft-Netzwerk, selbst gehostet', 'VectolynCloud: run your own Minecraft network'),
      description: t('VectolynCloud ist freie Software (GPL-3.0), die dein Minecraft-Netzwerk aufsetzt und betreibt: Proxy, Lobby, Spielserver und Minispiele, für Java- und Bedrock-Spieler.',
        'VectolynCloud is free software (GPL-3.0) that sets up and runs your Minecraft network: proxy, lobby, game servers and minigames, for Java and Bedrock players.'),
      crumbs: null,
      body: (ctx) => {
        const lead = team.find((m) => m.mcName && ctx.config.heads?.[m.mcName]);
        const leadHead = lead && ctx.config.heads[lead.mcName];
        const openRoles = team.filter((m) => !m.who).length;
        const nowStage = stages(t).findIndex(([, s]) => s === 'now') + 1;
        return `<section class="hero">
  <div class="hero-text">
    <h1>${t('Dein Minecraft-Netzwerk, betrieben von einem Programm.', 'Your Minecraft network, run by one program.')}</h1>
    <p class="lede">${t('VectolynCloud lädt die Serversoftware, prüft jede Datei, richtet die Datenbank ein und startet Proxy, Lobby und Spielserver. Java- und Bedrock-Spieler landen auf denselben Servern, und du hostest alles selbst.',
      'VectolynCloud downloads the server software, checks every file, sets up the database and starts proxy, lobby and game servers. Java and Bedrock players end up on the same servers, and you host all of it yourself.')}</p>
    <p class="actions">
      <a class="btn btn-primary btn-large" href="${ctx.link('download')}">${icon('download', 'btn-icon')}${t('Herunterladen', 'Download')}</a>
      <a class="btn btn-large" href="${ctx.link('network')}">${icon('crossplay', 'btn-icon')}${t('Zum Testnetzwerk', 'Test network')}</a>
    </p>
  </div>
  <div class="hero-art">
    ${worldVisual(t, ctx)}
    <div class="hero-deco" aria-hidden="true"><span></span><span></span><span></span><span></span><span></span></div>
  </div>
</section>

<section class="band" aria-labelledby="h-why">
  <p class="kicker">${t('Produkt', 'Product')}</p>
  <h2 id="h-why">${t('Was VectolynCloud dir abnimmt', 'What VectolynCloud takes off your hands')}</h2>
  <ul class="features">
    <li class="feature">${icon('crossplay')}<h3>${t('Java und Bedrock auf denselben Servern', 'Java and Bedrock on the same servers')}</h3><p>${t('Velocity ist der einzige Eingang. Geyser und Floodgate lassen Bedrock-Spieler herein, ViaVersion, ViaBackwards und ViaRewind übersetzen ältere Java-Versionen bis 1.8.', 'Velocity is the single entry point. Geyser and Floodgate let Bedrock players in, ViaVersion, ViaBackwards and ViaRewind translate older Java versions back to 1.8.')}</p></li>
    <li class="feature">${icon('check')}<h3>${t('Geprüfte Downloads', 'Verified downloads')}</h3><p>${t('Paper, Velocity, Fabric und die Plugins kommen von den offiziellen Quellen und werden gegen ihre Prüfsumme geprüft, bevor ein Server sie startet.', 'Paper, Velocity, Fabric and the plugins come from their official sources and are checked against their checksum before a server starts them.')}</p></li>
    <li class="feature">${icon('setup')}<h3>${t('Setup ohne Handarbeit', 'Setup without busywork')}</h3><p>${t('Ein Assistent fragt beim ersten Start alles Nötige ab und schreibt erst, wenn du speicherst. Ohne Terminal liest er eine Setup-Datei.', 'An assistant asks what it needs on the first start and writes nothing until you save. Without a terminal it reads a setup file instead.')}</p></li>
    <li class="feature">${icon('scale')}<h3>${t('Minispiele nach Bedarf', 'Minigames on demand')}</h3><p>${t('Statische Server laufen dauerhaft, Minispiel-Server startet und beendet die Cloud, wenn Spieler sie brauchen.', 'Static servers keep running; the cloud starts and stops minigame servers as players need them.')}</p></li>
    <li class="feature">${icon('window')}<h3>${t('Läuft, wo du willst', 'Runs where you want')}</h3><p>${t('Windows, Linux mit systemd, macOS, Docker, Pelican und Pterodactyl. Nötig ist Java 21 oder neuer.', 'Windows, Linux with systemd, macOS, Docker, Pelican and Pterodactyl. You need Java 21 or newer.')}</p></li>
    <li class="feature">${icon('key')}<h3>${t('Offen und signiert', 'Open and signed')}</h3><p>${t('Der Quelltext steht unter GPL-3.0. Jede Release-Datei ist mit cosign signiert und hat eine SLSA-Herkunftsbescheinigung.', 'The source code is under GPL-3.0. Every release file is signed with cosign and carries SLSA build provenance.')}</p></li>
  </ul>
</section>

<section class="band band-split band-flow" aria-labelledby="h-flow">
  <div>
    <h2 id="h-flow">${t('So hängt alles zusammen', 'How it fits together')}</h2>
    <p>${t('Spieler kommen nur über den Proxy herein. Er verteilt sie auf Lobby, Survival, Creative und die Minispiele. VectolynCloud sitzt darunter: Sie startet jeden Server, beobachtet ihn und fährt Minispiel-Server je nach Andrang hoch oder herunter.', 'Players only come in through the proxy, which sends them on to lobby, survival, creative and the minigames. VectolynCloud sits underneath: it starts every server, watches it and adds or removes minigame servers as demand changes.')}</p>
    <p>${t('Datenbank und API hören standardmäßig nur lokal. Nach außen offen sind nur die Proxy-Ports.', 'Database and API only listen locally by default. Only the proxy ports are open to the outside.')}</p>
  </div>
  ${netVisual(t)}
</section>

<section class="band band-split" aria-labelledby="h-start">
  <div>
    <h2 id="h-start">${t('In vier Schritten zum laufenden Netzwerk', 'Four steps to a running network')}</h2>
    <ol class="steps">
      <li>${t('Archiv für dein System aus den Releases laden.', 'Download the archive for your system from the releases.')}</li>
      <li>${t('In einen leeren Ordner entpacken.', 'Unpack it into an empty folder.')}</li>
      <li>${t('<code>start.bat</code> oder <code>./start.sh</code> ausführen.', 'Run <code>start.bat</code> or <code>./start.sh</code>.')}</li>
      <li>${t('Die Fragen des Assistenten beantworten. Den Rest erledigt die Cloud.', 'Answer the assistant’s questions. The cloud does the rest.')}</li>
    </ol>
    <p><a class="text-link" href="${ctx.link('download')}">${t('Download und Voraussetzungen', 'Download and requirements')}</a></p>
  </div>
  <figure class="terminal" aria-labelledby="term-cap">
    <div class="term-bar" aria-hidden="true"><span></span><span></span><span></span></div>
    <pre tabindex="0"><code><span class="c"># ${t('Linux oder macOS, Java 21 oder neuer', 'Linux or macOS, Java 21 or newer')}</span>
<span class="p">$</span> tar -xzf VectolynCloud-&lt;version&gt;-linux-macos.tar.gz
<span class="p">$</span> ./start.sh
<span class="c"># ${t('Der Assistent fragt nach Sprache, EULA,', 'The assistant asks for language, EULA,')}
# ${t('Servern, Ports und Datenbank.', 'servers, ports and database.')}</span></code></pre>
    <figcaption id="term-cap">${t('Unter Windows startet start.bat dasselbe.', 'On Windows, start.bat does the same.')}</figcaption>
  </figure>
</section>

<section class="band band-network" aria-labelledby="h-test">
  <div class="network-inner">
    <div>
      <p class="kicker">${t('Netzwerk', 'Network')}</p>
      <h2 id="h-test">${t('Erst ausprobieren: Vectolyn Network', 'Try it first: Vectolyn Network')}</h2>
      <p>${t('Auf unserem öffentlichen Testnetzwerk siehst du, wie sich ein Netzwerk mit VectolynCloud anfühlt, bevor du dein eigenes aufsetzt. Es ist noch nicht online; die Adresse steht hier, sobald es läuft.', 'On our public test network you can see what a network run by VectolynCloud feels like before you set up your own. It is not online yet; the address will appear here once it runs.')}</p>
      <p class="actions"><a class="btn btn-primary" href="${ctx.link('network')}">${t('So spielst du mit', 'How to join')}</a> <a class="btn" href="${ctx.link('rules')}">${t('Regeln', 'Rules')}</a> <a class="text-link" href="${ctx.link('shop')}">${t('Shop im Testmodus', 'Shop in test mode')}</a></p>
    </div>
    <ul class="editions">
      <li>${icon('window', 'ed-icon')}<span class="ed-name">Java Edition</span><span class="ed-sub">${t('aktuelle Version, ältere bis 1.8', 'current version, older back to 1.8')}</span></li>
      <li>${icon('crossplay', 'ed-icon')}<span class="ed-name">Bedrock Edition</span><span class="ed-sub">${t('über Geyser und Floodgate', 'through Geyser and Floodgate')}</span></li>
    </ul>
  </div>
</section>

<section class="band band-split band-status" aria-labelledby="h-status">
  <div>
    <p class="kicker">Status</p>
    <h2 id="h-status">${t('Wo das Projekt steht', 'Where the project stands')}</h2>
    <dl class="bignums">
      <div><dt>${t('Projektstufe', 'Project stage')}</dt><dd><span class="num" data-count="${nowStage}">${nowStage}</span><span class="of">/ 8</span></dd><dd class="bar bar-${nowStage}" aria-hidden="true"><span></span></dd></div>
      <div><dt>${t('Java-Versionen', 'Java versions')}</dt><dd><span class="num">1.8</span><span class="of">${t('bis aktuell', 'to current')}</span></dd></div>
      <div><dt>${t('Plattformen', 'Platforms')}</dt><dd><span class="num" data-count="6">6</span><span class="of">${t('Systeme', 'systems')}</span></dd></div>
    </dl>
    ${roadmap(t)}
    <p>${t('Den Fortschritt je Aufgabe zeigt das', 'Progress per task is on the')} ${ext('https://github.com/users/VectoDE/projects/11', t('Roadmap-Board auf GitHub', 'roadmap board on GitHub'))}.</p>
  </div>
  ${statusCard(t, ctx)}
</section>

<section class="band" aria-labelledby="h-docs">
  <p class="kicker">${t('Doku', 'Docs')}</p>
  <h2 id="h-docs">${t('Alles zum Betrieb an einem Ort', 'Everything about running it in one place')}</h2>
  <ul class="doc-cards">
    <li><a class="card-link" href="${ctx.link('download')}">${icon('download')}<span class="cl-title">Download</span><span class="cl-desc">${t('Releases, Voraussetzungen, Signaturen prüfen', 'Releases, requirements, checking signatures')}</span></a></li>
    <li><a class="card-link" href="${ctx.link('docs')}">${icon('book')}<span class="cl-title">${t('Dokumentation', 'Documentation')}</span><span class="cl-desc">${t('Betrieb, Setup, Konsole und API', 'Operations, setup, console and API')}</span></a></li>
    <li><a class="card-link" href="${ctx.link('websites')}">${icon('setup')}<span class="cl-title">${t('Website für dein Netzwerk', 'A website for your network')}</span><span class="cl-desc">${t('Drei geplante Wege, mit Shop', 'Three planned ways, with a shop')}</span></a></li>
    <li><a class="card-link" href="${ctx.link('faq')}">${icon('news')}<span class="cl-title">${t('Häufige Fragen', 'FAQ')}</span><span class="cl-desc">${t('Kurze Antworten zu Lizenz, Betrieb und Testnetzwerk', 'Short answers on licence, operation and the test network')}</span></a></li>
  </ul>
</section>

<section class="band band-team" aria-labelledby="h-team">
  ${leadHead ? head3d(leadHead.key, ctx.esc(t(`Kopf von ${leadHead.name}`, `Head of ${leadHead.name}`) + (lead.person ? ` (${lead.person})` : '')), 'head-xl') : ''}
  <div>
    <p class="kicker">Team</p>
    <h2 id="h-team">${t('Wer dahintersteht', 'Who is behind it')}</h2>
    <p>${t(`Heute entwickelt Tim Hauke das Projekt allein. Für das Testnetzwerk sind ${openRoles} Rollen offen: Administration, Moderation, Support und Bau.`, `Today Tim Hauke develops the project alone. For the test network ${openRoles} roles are open: administration, moderation, support and building.`)}</p>
    <p class="actions"><a class="btn" href="${ctx.link('team')}">${t('Zum Team', 'Meet the team')}</a></p>
  </div>
</section>`;
      },
    };
  },

  download(lang, config) {
    const t = L(lang);
    const rel = config.release;
    const ops = `${BLOB}/docs/cloud/operations${lang === 'de' ? '.de' : ''}.md`;
    return {
      title: t('VectolynCloud herunterladen: Releases, Voraussetzungen, Signaturen', 'Download VectolynCloud: releases, requirements, signatures'),
      description: t('VectolynCloud für Windows, Linux, macOS, Docker, Pelican und Pterodactyl. Voraussetzungen, Schnellstart und wie du Prüfsummen und Signaturen prüfst.',
        'VectolynCloud for Windows, Linux, macOS, Docker, Pelican and Pterodactyl. Requirements, quick start and how to check checksums and signatures.'),
      type: 'WebPage',
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'download', name: 'Download' }],
      extraLd: (base) => [{
        '@type': 'SoftwareApplication', name: 'VectolynCloud', applicationCategory: 'GameApplication', applicationSubCategory: 'Minecraft server management',
        operatingSystem: 'Windows, Linux, macOS', downloadUrl: rel ? rel.url : REPO + '/releases', license: 'https://www.gnu.org/licenses/gpl-3.0.html',
        ...(rel ? { softwareVersion: rel.version } : {}),
        softwareRequirements: 'Java 21 or newer', author: { '@id': base + '#org' },
        offers: { '@type': 'Offer', price: '0', priceCurrency: 'EUR' },
      }],
      body: (ctx) => `${pageHead(t('Für Betreiber', 'For operators'), t('VectolynCloud herunterladen', 'Download VectolynCloud'),
        t('Die Dateien kommen direkt aus den Releases auf GitHub. Bisher gibt es nur Vorabversionen: zum Ausprobieren gedacht, noch nicht für ein Netzwerk mit echten Spielern.', 'The files come straight from the releases on GitHub. So far there are only pre-releases: meant for trying out, not yet for a network with real players.'))}
${rel ? downloadPicker(t, ctx, rel, lang) : `<section class="band">
  <p class="notice notice-warn">${t('Die Release-Daten konnten beim Bauen dieser Seite nicht geladen werden. Alle Dateien findest du auf der Release-Seite.', 'The release data could not be loaded when this page was built. All files are on the releases page.')}</p>
  <p class="actions"><a class="btn btn-primary btn-large" href="${REPO}/releases">${icon('download', 'btn-icon')}${t('Zu den Releases', 'Go to the releases')}</a></p>
</section>`}
<section class="band">
  <h2>${t('Das brauchst du', 'What you need')}</h2>
  <ul class="ticks">
    <li>${t('Java 21 oder neuer', 'Java 21 or newer')}</li>
    <li>${t('ein normales Benutzerkonto: als root oder Administrator startet die Cloud nicht', 'a normal user account: the cloud refuses to start as root or administrator')}</li>
    <li>${t('Internet beim ersten Start, damit die Serversoftware geladen werden kann', 'internet access on the first start, to download the server software')}</li>
  </ul>
  <h2>${t('Schnellstart', 'Quick start')}</h2>
  <ol class="steps">
    <li>${t('Archiv für dein System herunterladen und in einen leeren Ordner entpacken. Der Ordner wird das Zuhause der Cloud.', 'Download the archive for your system and unpack it into an empty folder. That folder is the cloud’s home.')}</li>
    <li>${t('Starten: <code>start.bat</code> unter Windows, <code>./start.sh</code> unter Linux und macOS.', 'Start it: <code>start.bat</code> on Windows, <code>./start.sh</code> on Linux and macOS.')}</li>
    <li>${t('Die Fragen des Setup-Assistenten beantworten. Er zeigt am Ende eine Zusammenfassung und schreibt erst, wenn du speicherst.', 'Answer the setup assistant. It shows a summary at the end and writes nothing until you save.')}</li>
    <li>${t('Die Cloud lädt die Serversoftware, prüft sie und startet das Netzwerk. <code>help</code> zeigt die Befehle, <code>shutdown</code> beendet alles.', 'The cloud downloads and checks the server software and starts the network. <code>help</code> lists the commands, <code>shutdown</code> stops everything.')}</li>
  </ol>
  <p>${t('Docker, Compose, systemd, Pelican und Pterodactyl beschreibt die', 'Docker, Compose, systemd, Pelican and Pterodactyl are covered in the')} ${ext(ops, t('Betriebsanleitung', 'operations guide'))}. ${t('Das Docker-Image heißt', 'The Docker image is')} <code>ghcr.io/vectode/vectolyn-cloud</code>${t(' und ist mit der Version getaggt.', ', tagged with the version.')}</p>
  <h2>${t('Standard-Ports', 'Default ports')}</h2>
  <div class="table-wrap"><table>
    <thead><tr><th scope="col">${t('Dienst', 'Service')}</th><th scope="col">Port</th></tr></thead>
    <tbody>
      <tr><td>${t('Proxy, Java', 'Proxy, Java')}</td><td>25565</td></tr>
      <tr><td>${t('Proxy, Bedrock (Geyser)', 'Proxy, Bedrock (Geyser)')}</td><td>19132</td></tr>
      <tr><td>${t('Proxy, Bedrock NetherNet (Geyser, UDP)', 'Proxy, Bedrock NetherNet (Geyser, UDP)')}</td><td>19133</td></tr>
    </tbody>
  </table></div>
  <p>${t('Nur die Proxy-Ports gehören nach außen. Datenbank und API hören standardmäßig nur auf 127.0.0.1.', 'Only the proxy ports should be reachable from outside. By default the database and the API listen on 127.0.0.1 only.')}</p>
  <h2 id="${t('pruefen', 'verify')}">${t('Download prüfen', 'Verify the download')}</h2>
  <p>${t('Jede Datei ist mit cosign signiert (keyless über Sigstore, ausgestellt für den Release-Workflow dieses Repositorys) und hat eine SLSA-Herkunftsbescheinigung. Im Ordner mit den heruntergeladenen Dateien:', 'Every file is signed with cosign (keyless through Sigstore, issued to the release workflow of this repository) and has SLSA build provenance. In the folder with the downloaded files:')}</p>
  <pre class="code" tabindex="0"><code>sha256sum --check --ignore-missing SHA256SUMS
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud</code></pre>
  <p>${t('Die vollständigen cosign-Befehle stehen in der', 'The full cosign commands are in the')} ${ext(`${BLOB}/docs/cloud/operations.md#verifying-a-release`, t('Anleitung zur Release-Prüfung', 'release verification guide'))}.</p>
</section>`,
    };
  },

  docs(lang) {
    const t = L(lang);
    const de = lang === 'de';
    const w = (page) => `${WIKI}/${page}${de ? '-DE' : ''}`;
    const groups = [
      [t('Einstieg', 'Getting started'), [
        [w('Overview'), t('Überblick', 'Overview'), t('Was Vectolyn ist und wie die Teile zusammenspielen.', 'What Vectolyn is and how the parts fit together.')],
        [w('Getting-Started'), t('Erste Schritte', 'Getting started'), t('Von der Installation bis zum ersten Spieler.', 'From installation to the first player.')],
        [w('Crossplay'), 'Crossplay', t('Wie Java- und Bedrock-Spieler zusammen spielen.', 'How Java and Bedrock players play together.')],
        [w('FAQ'), 'FAQ', t('Häufige Fragen im Wiki.', 'Frequently asked questions in the wiki.')],
      ]],
      [t('Betrieb', 'Running it'), [
        [`${BLOB}/docs/cloud/operations${de ? '.de' : ''}.md`, t('Betrieb', 'Operations'), t('Windows, Linux mit systemd, macOS, Docker, Pelican, Pterodactyl.', 'Windows, Linux with systemd, macOS, Docker, Pelican, Pterodactyl.')],
        [`${BLOB}/docs/cloud/setup${de ? '.de' : ''}.md`, t('Setup-Assistent', 'Setup assistant'), t('Alle Fragen und die Setup-Datei.', 'Every question and the setup file.')],
        [`${BLOB}/docs/cloud/console${de ? '.de' : ''}.md`, t('Konsole', 'Console'), t('Befehle der Konsole.', 'Console commands.')],
        [`${BLOB}/docs/cloud/config.md`, t('Konfiguration (englisch)', 'Configuration'), t('Jeder Schlüssel in cloud.yml.', 'Every key in cloud.yml.')],
        [`${BLOB}/docs/cloud/backup.md`, t('Backups (englisch)', 'Backups'), t('Sichern und Wiederherstellen.', 'Backing up and restoring.')],
        [`${BLOB}/docs/cloud/monitor.md`, t('Monitoring (englisch)', 'Monitoring'), t('Metriken und Warnungen.', 'Metrics and alerts.')],
        [`${BLOB}/docs/cloud/scaling${de ? '.de' : ''}.md`, t('Skalierung', 'Scaling'), t('Wann die Cloud Server startet und stoppt.', 'When the cloud starts and stops servers.')],
        [`${BLOB}/docs/cloud/privacy.md`, t('Datenschutz im Betrieb (englisch)', 'Data protection'), t('Was gespeichert wird und was du als Betreiber beachten musst.', 'What gets stored and what you have to consider as an operator.')],
      ]],
      [t('Entwicklung', 'Development'), [
        [`${BLOB}/docs/cloud/api${de ? '.de' : ''}.md`, 'REST, WebSocket, GraphQL, gRPC', t('Die API des Knotens.', 'The API of the node.')],
        [ctx_api(), t('OpenAPI-Datei', 'OpenAPI file'), t('Maschinenlesbare Beschreibung der REST-API.', 'Machine-readable description of the REST API.')],
        [`${BLOB}/docs/cloud/architecture.md`, t('Architektur (englisch)', 'Architecture'), t('Module und ihr Zusammenspiel.', 'Modules and how they interact.')],
        [`${BLOB}/docs/development/README.md`, t('Entwickler-Doku (englisch)', 'Developer docs'), t('Build, Tests, Module, Releases.', 'Build, tests, modules, releases.')],
        [`${REPO}/blob/main/CONTRIBUTING${de ? '.de' : ''}.md`, t('Mitmachen', 'Contributing'), t('Wie du zum Projekt beiträgst.', 'How to contribute.')],
      ]],
    ];
    return {
      title: t('Dokumentation zu VectolynCloud: Betrieb, Setup, API', 'VectolynCloud documentation: operations, setup, API'),
      description: t('Anleitungen für Betreiber und Entwickler: Installation, Setup-Assistent, Konsole, Konfiguration, Backups, Skalierung und die API von VectolynCloud.',
        'Guides for operators and developers: installation, setup assistant, console, configuration, backups, scaling and the API of VectolynCloud.'),
      type: 'CollectionPage',
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'docs', name: t('Dokumentation', 'Documentation') }],
      body: (ctx) => `${pageHead(t('Für Betreiber', 'For operators'), t('Dokumentation', 'Documentation'),
        t('Die Dokumentation liegt im Repository und im Wiki, damit sie mit dem Code zusammen geprüft und geändert wird. Hier findest du den richtigen Einstieg.', 'The documentation lives in the repository and the wiki, so it is reviewed and changed together with the code. This page points you to the right place.'))}
<section class="band">
${groups.map(([h, items]) => `  <h2>${h}</h2>
  <ul class="link-list">${items.map(([href, name, text]) => `<li><a href="${href === '@api' ? ctx.asset('api/openapi.yaml') : href}">${name}</a><span>${text}</span></li>`).join('')}</ul>`).join('\n')}
  <h2>${t('Website für dein Netzwerk', 'A website for your network')}</h2>
  <ul class="link-list"><li><a href="${ctx.link('websites')}">${t('Drei Wege zur Website', 'Three ways to a website')}</a><span>${t('Vorlage, Baukasten oder eigener Code: was geplant ist.', 'Template, block builder or your own code: what is planned.')}</span></li></ul>
</section>`,
    };
  },

  websites(lang) {
    const t = L(lang);
    return {
      title: t('Website für dein Minecraft-Netzwerk: drei geplante Wege', 'A website for your Minecraft network: three planned ways'),
      description: t('Vorlage, Baukasten mit HTML-Blöcken im Panel oder reines Code-Projekt: so sollen Betreiber mit VectolynCloud zu einer eigenen Netzwerk-Website kommen.',
        'Template, HTML block builder in the panel, or a plain code project: how VectolynCloud is meant to help operators get their own network website.'),
      type: 'TechArticle',
      section: 'docs',
      ld: { headline: t('Drei Wege zur Website', 'Three ways to a website') },
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'docs', name: t('Dokumentation', 'Documentation') }, { key: 'websites', name: t('Website', 'Website') }],
      body: () => `${pageHead(t('Geplant für Stufe 8', 'Planned for stage 8'), t('Drei Wege zur Website', 'Three ways to a website'),
        t('Ein Netzwerk braucht eine Seite mit Adresse, Regeln und Shop. VectolynCloud soll dafür drei Wege anbieten. Keiner davon ist fertig; diese Seite beschreibt den Plan.', 'A network needs a page with its address, rules and shop. VectolynCloud is meant to offer three ways to build one. None of them is finished; this page describes the plan.'))}
<section class="band">
  <ol class="ways">
    <li><h2>${t('1. Eigenes Projekt aus der Vorlage', '1. Your own project from the template')}</h2>
      <p>${t('Du bekommst eine fertige Website wie diese hier: zweisprachig, ohne Framework, mit Shop-Seiten, Regeln, Team und Statusanzeige. Du trägst Namen, Adresse und Texte ein, baust sie und legst die Dateien auf einen beliebigen Webspace.', 'You get a finished website like this one: two languages, no framework, with shop pages, rules, team and a status display. You fill in name, address and texts, build it and put the files on any web host.')}</p>
      <p class="tag">${t('Stand: Der Generator dieser Website liegt schon im Repository (site/). Als Vorlage für andere Netzwerke ist er noch nicht aufbereitet.', 'Status: the generator of this website is already in the repository (site/). It is not yet prepared as a template for other networks.')}</p></li>
    <li><h2>${t('2. Baukasten mit HTML-Blöcken im Panel', '2. Block builder with HTML blocks in the panel')}</h2>
      <p>${t('Im Web-Panel von VectolynCloud stellst du Seiten aus Blöcken zusammen: Kopf mit Serveradresse, Spielmodi, Regeln, Team, Shop-Kacheln, freier Text. Die Blöcke sind barrierefrei und mehrsprachig vorbereitet, eigenes HTML ist möglich. Das Panel veröffentlicht die Seiten selbst.', 'In the VectolynCloud web panel you put pages together from blocks: header with server address, game modes, rules, team, shop tiles, free text. The blocks come prepared for accessibility and several languages, and custom HTML is possible. The panel publishes the pages itself.')}</p>
      <p class="tag">${t('Stand: geplant, noch nicht begonnen.', 'Status: planned, not started.')}</p></li>
    <li><h2>${t('3. Reines Code-Projekt', '3. A plain code project')}</h2>
      <p>${t('Du baust deine Website mit dem Werkzeug deiner Wahl und holst Live-Daten über die API des Knotens: Spieler online, Server, Status. Die REST-API mit OpenAPI-Beschreibung gibt es schon; ein öffentlicher, tokenfreier Status-Endpunkt für Websites fehlt noch.', 'You build your website with the tool you like and pull live data from the node API: players online, servers, status. The REST API with its OpenAPI description exists already; a public, token-free status endpoint for websites is still missing.')}</p>
      <p class="tag">${t('Stand: API vorhanden, Status-Endpunkt geplant.', 'Status: API available, status endpoint planned.')}</p></li>
  </ol>
  <p>${t('Fortschritt und Diskussion:', 'Progress and discussion:')} ${ext(REPO + '/issues/191', t('Issue #191', 'issue #191'))}.</p>
</section>`,
    };
  },

  network(lang) {
    const t = L(lang);
    return {
      title: t('Vectolyn Network: das Testnetzwerk für VectolynCloud', 'Vectolyn Network: the VectolynCloud test network'),
      description: t('Ein öffentliches Minecraft-Testnetzwerk, auf dem du VectolynCloud ausprobieren kannst. Für Java und Bedrock. Noch nicht online, die Adresse folgt.',
        'A public Minecraft test network where you can try VectolynCloud. For Java and Bedrock. Not online yet, the address will follow.'),
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'network', name: t('Testnetzwerk', 'Test network') }],
      body: (ctx) => `${pageHead(t('Für Spieler', 'For players'), 'Vectolyn Network',
        t('Das Testnetzwerk zeigt, wie ein Netzwerk mit VectolynCloud läuft: Lobby, Spielserver, Crossplay. Wer überlegt, VectolynCloud selbst zu betreiben, kann hier vorher reinschauen.', 'The test network shows what a network run by VectolynCloud is like: lobby, game servers, crossplay. If you are thinking about running VectolynCloud yourself, you can have a look here first.'))}
<section class="band band-split">
  <div>
    <h2>${t('Status', 'Status')}</h2>
    <p>${t('Das Testnetzwerk ist noch nicht online. Die Adresse gibt es, sobald die eigene Domain steht und die Server laufen.', 'The test network is not online yet. The address follows once our own domain is set up and the servers are running.')}</p>
  </div>
  ${statusCard(t, ctx)}
</section>
<section class="band">
  <h2>${t('Womit du spielen kannst', 'What you can play with')}</h2>
  <ul class="cards cards-3">
    <li><h3>Java Edition</h3><p>${t('Die aktuelle Version und ältere Versionen von 1.8 bis 1.21.', 'The current version and older versions from 1.8 to 1.21.')}</p></li>
    <li><h3>Bedrock Edition</h3><p>${t('Über Geyser, also auch von Konsole, Handy oder Windows-App.', 'Through Geyser, so from consoles, phones or the Windows app as well.')}</p></li>
    <li><h3>${t('Ein Netzwerk für beide', 'One network for both')}</h3><p>${t('Java- und Bedrock-Spieler treffen sich auf denselben Servern.', 'Java and Bedrock players meet on the same servers.')}</p></li>
  </ul>
  <h2>${t('So kommst du rein', 'How to join')}</h2>
  <div class="cols-2">
    <div><h3>Java</h3><ol class="steps">
      <li>${t('Minecraft starten, Mehrspieler wählen.', 'Start Minecraft and choose Multiplayer.')}</li>
      <li>${t('Server hinzufügen und die Adresse eintragen.', 'Add a server and enter the address.')}</li>
      <li>${t('Verbinden.', 'Join.')}</li></ol></div>
    <div><h3>Bedrock</h3><ol class="steps">
      <li>${t('Minecraft starten, Spielen und dann Server wählen.', 'Start Minecraft, choose Play, then Servers.')}</li>
      <li>${t('Server hinzufügen: Adresse und Port eintragen.', 'Add a server: enter address and port.')}</li>
      <li>${t('Verbinden.', 'Join.')}</li></ol></div>
  </div>
  <p>${t('Adresse und Bedrock-Port stehen oben im Statuskasten, sobald das Netzwerk online ist.', 'Address and Bedrock port will show in the status box above once the network is online.')}</p>
  <h2>${t('Bevor du spielst', 'Before you play')}</h2>
  <p>${t('Lies die', 'Read the')} <a href="${ctx.link('rules')}">${t('Regeln', 'rules')}</a>. ${t('Der', 'The')} <a href="${ctx.link('shop')}">Shop</a> ${t('verkauft nur Dinge ohne Spielvorteil und läuft bis auf Weiteres im Testmodus.', 'only sells things without a gameplay advantage and runs in test mode for now.')}</p>
  <p>Discord: <span class="placeholder">${t('Server folgt, Link kommt hierher', 'server to follow, the link will go here')}</span></p>
</section>`,
    };
  },

  rules(lang) {
    const t = L(lang);
    const rules = [
      ['respekt', 'respect', t('Respekt', 'Respect'), [
        t('Beleidigungen, Drohungen, Belästigung und Diskriminierung sind verboten, im Chat, in Namen, auf Schildern und in Bauwerken.', 'Insults, threats, harassment and discrimination are not allowed: in chat, in names, on signs and in builds.'),
        t('Gib keine persönlichen Daten anderer weiter, auch nicht „aus Spaß“.', 'Do not share other people’s personal data, not even as a joke.'),
      ]],
      ['chat', 'chat', 'Chat', [
        t('Kein Spam, keine Kettenbotschaften, keine Werbung für andere Server oder Angebote.', 'No spam, no chain messages, no advertising for other servers or offers.'),
        t('Links nur, wenn sie zum Gespräch gehören. Keine Links zu Schadsoftware, Betrug oder Inhalten für Erwachsene.', 'Links only when they belong to the conversation. No links to malware, scams or adult content.'),
      ]],
      ['fair', 'fair-play', t('Fair spielen', 'Fair play'), [
        t('Keine Hack-Clients, keine Mods oder Ressourcenpakete mit Vorteil (zum Beispiel X-Ray), keine Makros und Autoklicker.', 'No hacked clients, no mods or resource packs that give an advantage (X-ray, for example), no macros or auto clickers.'),
        t('Fehler im Spiel meldest du über den Support, statt sie auszunutzen. Wer einen Fehler meldet, bekommt keinen Ärger.', 'Report bugs through support instead of exploiting them. Reporting a bug never gets you in trouble.'),
      ]],
      ['konten', 'accounts', t('Konten', 'Accounts'), [
        t('Du bist für alles verantwortlich, was über dein Konto passiert. Konten werden nicht gehandelt oder verkauft.', 'You are responsible for everything that happens through your account. Accounts are not traded or sold.'),
        t('Eine Sperre gilt für die Person, nicht nur für das Konto. Ein Zweitkonto zum Umgehen verlängert sie.', 'A ban applies to the person, not just the account. A second account to get around it extends the ban.'),
      ]],
      ['bauen', 'building', t('Bauen', 'Building'), [
        t('Fremde Bauwerke zerstören oder verändern (Griefing) ist verboten.', 'Destroying or changing other players’ builds (griefing) is not allowed.'),
        t('Keine Bauwerke, die Lag erzeugen sollen.', 'No builds made to cause lag.'),
      ]],
      ['team', 'staff', t('Team und Streit', 'Staff and disputes'), [
        t('Folge den Anweisungen des Teams. Bist du anderer Meinung, klär das über den Support, nicht im öffentlichen Chat.', 'Follow the instructions of staff. If you disagree, sort it out through support, not in public chat.'),
        t('Niemand aus dem Team fragt dich nach deinem Passwort.', 'No staff member will ask you for your password.'),
      ]],
      ['shop', 'shop', 'Shop', [
        t('Gekaufte Inhalte sind kosmetisch und bringen keinen Vorteil im Spiel.', 'Purchased items are cosmetic and give no advantage in the game.'),
        t('Wer eine Zahlung zurückbucht, ohne vorher den Support zu fragen, verliert bis zur Klärung den Zugriff auf die gekauften Inhalte.', 'If you charge back a payment without contacting support first, you lose access to the purchased items until the case is settled.'),
      ]],
    ];
    const id = (r) => (lang === 'de' ? r[0] : r[1]);
    return {
      title: t('Regeln im Vectolyn Network', 'Rules of Vectolyn Network'),
      description: t('Die Regeln des Testnetzwerks Vectolyn Network: Respekt, Chat, faires Spiel, Konten, Bauen, Team und Shop, dazu die Stufen der Strafen und der Weg zur Entsperrung.',
        'The rules of the Vectolyn Network test network: respect, chat, fair play, accounts, building, staff and shop, plus the levels of penalties and how to appeal.'),
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'network', name: t('Testnetzwerk', 'Test network') }, { key: 'rules', name: t('Regeln', 'Rules') }],
      section: 'network',
      body: (ctx) => `${pageHead('Vectolyn Network', t('Regeln', 'Rules'),
        t('Die Regeln gelten ab dem Start des Testnetzwerks auf allen seinen Servern. Stand: 12. November 2026.', 'These rules apply from the launch of the test network, on all of its servers. Last updated November 12, 2026.'))}
<section class="band band-rules">
  <nav class="toc" aria-label="${t('Regeln', 'Rules')}"><ol>${rules.map((r, i) => `<li><a href="#${id(r)}">${i + 1}. ${r[2]}</a></li>`).join('')}<li><a href="#${t('strafen', 'penalties')}">${t('Strafen und Entsperrung', 'Penalties and appeals')}</a></li></ol></nav>
  <div class="rules">
${rules.map((r, i) => `  <section id="${id(r)}" aria-labelledby="${id(r)}-h"><h2 id="${id(r)}-h">${i + 1}. ${r[2]}</h2><ol class="rule-list">${r[3].map((x, j) => `<li><span class="num">${i + 1}.${j + 1}</span> ${x}</li>`).join('')}</ol></section>`).join('\n')}
  <section id="${t('strafen', 'penalties')}" aria-labelledby="pen-h"><h2 id="pen-h">${t('Strafen und Entsperrung', 'Penalties and appeals')}</h2>
    <p>${t('Das Team entscheidet nach Schwere und Wiederholung. Üblich ist diese Reihenfolge:', 'Staff decide by severity and repetition. The usual order is:')}</p>
    <ol class="steps"><li>${t('Hinweis', 'Warning')}</li><li>${t('Stummschaltung auf Zeit', 'Temporary mute')}</li><li>${t('Sperre auf Zeit', 'Temporary ban')}</li><li>${t('Dauerhafte Sperre', 'Permanent ban')}</li></ol>
    <p>${t('Bei Betrug, Cheats oder schweren Beleidigungen kann das Team Stufen überspringen. Hältst du eine Strafe für falsch, schreib dem', 'For fraud, cheating or serious abuse, staff can skip levels. If you think a penalty is wrong, contact')} <a href="${ctx.link('support')}">Support</a>${t('. Jemand, der nicht an der Entscheidung beteiligt war, sieht sich den Fall an.', '. Someone who was not involved in the decision will look at the case.')}</p>
  </section>
  </div>
</section>`,
    };
  },

  shop(lang, config) {
    const t = L(lang);
    const cats = [
      ['raenge', 'ranks', t('Ränge', 'Ranks'), t('Ein Rang gibt dir Farbe und Präfix im Chat und in der Tabliste und Zugang zu kosmetischen Extras. Mehr Rechte im Spiel gibt er nicht.', 'A rank gives you a colour and prefix in chat and the tab list and access to cosmetic extras. It gives no extra powers in the game.'), 'crown'],
      ['kosmetik', 'cosmetics', t('Kosmetik', 'Cosmetics'), t('Zum Beispiel Partikel, Hüte oder ein Effekt beim Betreten der Lobby. Nur Aussehen, kein Vorteil.', 'Particles, hats or an effect when you enter the lobby, for example. Looks only, no advantage.'), 'sparkle'],
      ['zeitkaeufe', 'time-based', t('Zeitkäufe', 'Time-based purchases'), t('Ein Rang oder ein Kosmetik-Paket für einen festen Zeitraum. Es verlängert sich nicht automatisch und endet mit der Laufzeit.', 'A rank or cosmetic bundle for a fixed period. It does not renew automatically and ends with the period.'), 'hourglass'],
      ['digital', 'digital', t('Digitale Produkte', 'Digital products'), t('Dinge ohne Bezug zum Spiel, etwa Hintergrundbilder im Vectolyn-Stil.', 'Things with no link to the game, such as wallpapers in the Vectolyn style.'), 'picture'],
    ];
    const id = (c) => (lang === 'de' ? c[0] : c[1]);
    return {
      title: t('Shop im Vectolyn Network: kosmetisch, im Testmodus', 'Vectolyn Network shop: cosmetic, in test mode'),
      description: t('Ränge, Kosmetik, Zeitkäufe und digitale Produkte für das Testnetzwerk, ohne Spielvorteil. Bezahlung über Stripe oder Mollie, derzeit nur im Testmodus.',
        'Ranks, cosmetics, time-based purchases and digital products for the test network, with no gameplay advantage. Payment through Stripe or Mollie, currently in test mode only.'),
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'shop', name: 'Shop' }],
      body: (ctx) => `${pageHead('Vectolyn Network', 'Shop',
        t('Mit einem Kauf unterstützt du das Projekt. Was du bekommst, ist kosmetisch. Wer nichts kauft, hat dieselben Chancen.', 'A purchase supports the project. What you get is cosmetic. Players who buy nothing have the same chances.'))}
<section class="band">
  <p class="notice"><strong>${t('Testmodus.', 'Test mode.')}</strong> ${config.shop.liveApproved ? '' : t('Der Shop ist noch nicht geöffnet. Zahlungen laufen nur gegen die Testumgebungen von Stripe und Mollie, es wird kein echtes Geld abgebucht. Preise veröffentlichen wir zum Start.', 'The shop is not open yet. Payments only run against the test environments of Stripe and Mollie; no real money is charged. Prices will be published at launch.')}</p>
  <nav class="chips" aria-label="${t('Kategorien', 'Categories')}"><ul>${cats.map((c) => `<li><a href="#${id(c)}">${c[2]}</a></li>`).join('')}</ul></nav>
  <ul class="shop-grid">${cats.map((c) => `<li class="shop-card" id="${id(c)}"><div class="art" aria-hidden="true"><svg viewBox="0 0 16 16" width="72" height="72" focusable="false"><path fill-rule="evenodd" d="${SHOP_ART[c[4]]}"/></svg></div><div class="body"><h2>${c[2]}</h2><p>${c[3]}</p><div class="foot"><p class="price">${t('Preis folgt', 'Price to follow')}</p><span class="badge">${t('Testmodus', 'Test mode')}</span></div></div></li>`).join('')}</ul>
</section>
<section class="band">
  <h2>${t('Geplante Zahlarten', 'Planned payment methods')}</h2>
  <p>${t('Abgewickelt über Stripe oder Mollie. Im Testmodus laufen alle mit Testschlüsseln, es fließt kein Geld.', 'Handled by Stripe or Mollie. In test mode all of them run with test keys, and no money moves.')}</p>
  <ul class="pay">${[t('Kredit- und Debitkarte', 'Credit and debit card'), t('SEPA-Lastschrift', 'SEPA Direct Debit'), 'PayPal', t('Sofortüberweisung und vergleichbare Bankverfahren', 'Instant bank transfer and similar bank methods'), 'Apple Pay', 'Google Pay'].map((m) => `<li>${m}</li>`).join('')}</ul>
  <h2>${t('So läuft ein Kauf', 'How a purchase works')}</h2>
  <ol class="steps">
    <li>${t('Artikel wählen und deinen Minecraft-Namen eingeben. Der Shop prüft, ob es den Namen gibt.', 'Choose an item and enter your Minecraft name. The shop checks that the name exists.')}</li>
    <li>${t('Bestellübersicht prüfen: Artikel, Laufzeit, Endpreis mit Umsatzsteuer.', 'Check the order summary: item, duration, final price including VAT.')}</li>
    <li>${t('Mit „Zahlungspflichtig bestellen“ geht es zu Stripe oder Mollie. Kartendaten sieht nur der Zahlungsanbieter.', 'The “Order with obligation to pay” button takes you to Stripe or Mollie. Only the payment provider sees your card details.')}</li>
    <li>${t('Nach der Zahlung kommt der Artikel automatisch ins Spiel, auch wenn du gerade offline bist.', 'After payment the item is delivered in the game automatically, even if you are offline at the time.')}</li>
  </ol>
  <h2>${t('Gut zu wissen', 'Good to know')}</h2>
  <ul class="ticks">
    <li>${t('Ein Kundenkonto mit E-Mail und Passwort zeigt deine Bestellungen und Rechnungen.', 'A customer account with e-mail and password shows your orders and invoices.')}</li>
    <li>${t('Nach der Zahlung bekommst du eine Rechnung als PDF mit fortlaufender Nummer. Wie die Umsatzsteuer darauf ausgewiesen wird, klären wir vor dem Start mit dem Steuerberater.', 'After payment you get an invoice as a PDF with a running number. How VAT is shown on it will be settled with our tax adviser before launch.')}</li>
    <li>${t('Für digitale Inhalte gelten beim Widerrufsrecht Besonderheiten. Die Widerrufsbelehrung und die AGB stehen vor dem Start hier und im Bestellablauf.', 'For digital content, the right of withdrawal has special rules. The withdrawal policy and the terms will be published here and in the checkout before launch.')}</li>
    <li>${t('Fragen zu einer Bestellung beantwortet der', 'Questions about an order go to')} <a href="${ctx.link('support')}">Support</a>.</li>
  </ul>
</section>`,
    };
  },

  team(lang) {
    const t = L(lang);
    return {
      title: t('Team hinter VectolynCloud und dem Vectolyn Network', 'The team behind VectolynCloud and Vectolyn Network'),
      description: t('Wer VectolynCloud entwickelt und welche Aufgaben im Team des Testnetzwerks Vectolyn Network noch offen sind.',
        'Who develops VectolynCloud and which roles in the Vectolyn Network test network team are still open.'),
      type: 'AboutPage',
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'team', name: 'Team' }],
      extraLd: (base) => [{ '@type': 'Person', name: 'Tim Hauke', alternateName: 'VectoDE', url: 'https://github.com/VectoDE', jobTitle: t('Leitung und Entwicklung', 'Lead and development'), worksFor: { '@id': base + '#org' } }],
      body: (ctx) => `${pageHead('', 'Team',
        t('VectolynCloud ist ein Gemeinschaftsprojekt. Heute steckt eine Person dahinter; das Team des Testnetzwerks entsteht vor dessen Start.', 'VectolynCloud is a community project. Right now one person is behind it; the test network team will come together before it launches.'))}
<section class="band">
  <ul class="team">${team.map((m) => {
    const head = m.mcName && ctx.config.heads?.[m.mcName];
    const label = head ? t(`Kopf von ${head.name}`, `Head of ${head.name}`) + (m.person ? ` (${m.person})` : '') : t('Offene Stelle, noch ohne Kopf', 'Open position, no head yet');
    return `<li class="${m.who ? '' : 'open'}">${head3d(head && head.key, ctx.esc(label), 'head-lg')}<h2>${m.role[lang]}</h2><p>${m.what[lang]}</p><p class="who">${m.who || t('noch offen', 'not filled yet')}</p></li>`;
  }).join('')}</ul>
  <p>${t('Du willst mitmachen? Code, Übersetzungen und Dokumentation kannst du jederzeit beitragen, siehe', 'Want to help? You can contribute code, translations and documentation at any time, see the')} ${ext(REPO + '/blob/main/CONTRIBUTING' + (lang === 'de' ? '.de' : '') + '.md', t('Anleitung zum Mitmachen', 'contributing guide'))}. ${t('Bewerbungen für Moderation und Support öffnen wir vor dem Start des Testnetzwerks.', 'Applications for moderation and support open before the test network launches.')}</p>
</section>`,
    };
  },

  support(lang) {
    const t = L(lang);
    return {
      title: t('Support für VectolynCloud und das Vectolyn Network', 'Support for VectolynCloud and Vectolyn Network'),
      description: t('Wo du Hilfe bekommst: Fragen zur Software, Fehlerberichte, Sicherheitslücken und später Anliegen rund um das Testnetzwerk und den Shop.',
        'Where to get help: questions about the software, bug reports, security issues and, later, anything about the test network and the shop.'),
      type: 'ContactPage',
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'support', name: 'Support' }],
      body: (ctx) => `${pageHead('', 'Support', t('Such dir den Weg, der zu deinem Anliegen passt. Vieles steht schon in den', 'Pick the route that fits your question. A lot is answered in the') + ` <a href="${ctx.link('faq')}">${t('häufigen Fragen', 'FAQ')}</a>.`)}
<section class="band">
  <ul class="cards cards-2">
    <li><h2>${t('Frage zur Software', 'A question about the software')}</h2><p>${t('Installation, Konfiguration, Ideen: in den Diskussionen auf GitHub. Antworten bleiben dort für alle sichtbar.', 'Installation, configuration, ideas: in the discussions on GitHub. Answers stay visible there for everyone.')}</p><p><a class="btn" href="${REPO}/discussions">${t('Zu den Diskussionen', 'Open discussions')}</a></p></li>
    <li><h2>${t('Fehler gefunden', 'Found a bug')}</h2><p>${t('Beschreib, was du getan hast, was passiert ist und was du erwartet hast, und häng das Log an.', 'Describe what you did, what happened and what you expected, and attach the log.')}</p><p><a class="btn" href="${REPO}/issues/new/choose">${t('Fehler melden', 'Report a bug')}</a></p></li>
    <li><h2>${t('Sicherheitslücke', 'Security issue')}</h2><p>${t('Bitte nicht öffentlich melden. Der Weg für vertrauliche Meldungen steht in der Sicherheitsrichtlinie.', 'Please do not report it in public. The route for private reports is in the security policy.')}</p><p><a class="btn" href="${REPO}/security/policy">${t('Sicherheitsrichtlinie', 'Security policy')}</a></p></li>
    <li><h2>${t('Testnetzwerk und Shop', 'Test network and shop')}</h2><p>${t('Konto, Strafen, Bestellungen: dafür öffnen wir zum Start des Testnetzwerks einen eigenen Kontaktweg.', 'Account, penalties, orders: a separate contact route opens when the test network launches.')}</p><p>${t('E-Mail:', 'E-mail:')} <span class="placeholder">${t('folgt mit der eigenen Domain', 'follows with our own domain')}</span><br>Discord: <span class="placeholder">${t('folgt', 'to follow')}</span></p></li>
  </ul>
  <p>${t('Wir betreuen das Projekt in unserer Freizeit und können deshalb keine festen Antwortzeiten versprechen.', 'We maintain the project in our spare time, so we cannot promise fixed response times.')}</p>
</section>`,
    };
  },

  faq(lang) {
    const t = L(lang);
    const qa = [
      [t('Was ist VectolynCloud?', 'What is VectolynCloud?'), t('Ein Programm, das ein komplettes Minecraft-Netzwerk aufsetzt und betreibt: Es lädt und prüft die Serversoftware, richtet die Datenbank ein, startet Proxy, Lobby und Spielserver und skaliert Minispiel-Server. Es ist freie Software unter GPL-3.0.', 'A program that sets up and runs a complete Minecraft network: it downloads and checks the server software, sets up the database, starts proxy, lobby and game servers and scales minigame servers. It is free software under GPL-3.0.')],
      [t('Was kostet VectolynCloud?', 'What does VectolynCloud cost?'), t('Nichts. Du brauchst nur einen eigenen Server oder Rechner, auf dem das Netzwerk läuft.', 'Nothing. You only need your own server or computer for the network to run on.')],
      [t('Ist VectolynCloud schon fertig?', 'Is VectolynCloud finished?'), t('Nein. Das Projekt ist in Stufe 2 von 8. Die bisherigen Releases sind Vorabversionen zum Ausprobieren, nicht für ein Netzwerk mit echten Spielern.', 'No. The project is in stage 2 of 8. The releases so far are pre-releases for trying out, not for a network with real players.')],
      [t('Auf welchen Systemen läuft es?', 'Which systems does it run on?'), t('Windows, Linux (auch mit systemd), macOS, Docker, Pelican und Pterodactyl. Voraussetzung ist Java 21 oder neuer.', 'Windows, Linux (including systemd), macOS, Docker, Pelican and Pterodactyl. It needs Java 21 or newer.')],
      [t('Können Java- und Bedrock-Spieler zusammen spielen?', 'Can Java and Bedrock players play together?'), t('Ja. Geyser und Floodgate lassen Bedrock-Spieler auf den Proxy, die Via-Plugins übersetzen ältere Java-Versionen.', 'Yes. Geyser and Floodgate let Bedrock players onto the proxy, and the Via plugins translate older Java versions.')],
      [t('Was ist das Vectolyn Network?', 'What is Vectolyn Network?'), t('Ein öffentliches Testnetzwerk, das mit VectolynCloud läuft. Dort kannst du ausprobieren, wie sich ein solches Netzwerk anfühlt. Es ist noch nicht online.', 'A public test network that runs on VectolynCloud, so you can see what such a network feels like. It is not online yet.')],
      [t('Wann startet das Testnetzwerk?', 'When does the test network launch?'), t('Einen Termin gibt es noch nicht. Die Adresse steht auf der Seite zum Testnetzwerk, sobald es läuft.', 'There is no date yet. The address will be on the test network page once it runs.')],
      [t('Bringt der Shop Vorteile im Spiel?', 'Does the shop give an advantage in the game?'), t('Nein. Ränge, Kosmetik und Zeitkäufe ändern nur das Aussehen. Der Shop ist außerdem noch im Testmodus, kaufen kann man dort noch nichts.', 'No. Ranks, cosmetics and time-based purchases only change how things look. The shop is also still in test mode; nothing can be bought yet.')],
      [t('Hat Vectolyn etwas mit Mojang oder Microsoft zu tun?', 'Is Vectolyn connected to Mojang or Microsoft?'), t('Nein. Vectolyn ist ein unabhängiges Gemeinschaftsprojekt.', 'No. Vectolyn is an independent community project.')],
    ];
    return {
      title: t('Häufige Fragen zu VectolynCloud und dem Testnetzwerk', 'FAQ: VectolynCloud and the test network'),
      description: t('Antworten zu Kosten, Stand, Systemen, Crossplay, dem Testnetzwerk Vectolyn Network und dem Shop ohne Spielvorteil.',
        'Answers on cost, project status, systems, crossplay, the Vectolyn Network test network and the shop without gameplay advantage.'),
      type: 'FAQPage',
      ld: { mainEntity: qa.map(([q, a]) => ({ '@type': 'Question', name: q, acceptedAnswer: { '@type': 'Answer', text: a } })) },
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'faq', name: t('Häufige Fragen', 'FAQ') }],
      body: (ctx) => `${pageHead('', t('Häufige Fragen', 'Frequently asked questions'), '')}
<section class="band">
  <div class="faq">${qa.map(([q, a]) => `<details><summary>${q}</summary><p>${a}</p></details>`).join('')}</div>
  <p>${t('Deine Frage fehlt?', 'Question not answered?')} <a href="${ctx.link('support')}">${t('Zum Support', 'Go to support')}</a>.</p>
</section>`,
    };
  },

  imprint(lang, config) {
    const t = L(lang);
    return {
      title: t('Impressum', 'Imprint'),
      description: t('Impressum der Website von VectolynCloud und dem Vectolyn Network.', 'Imprint (legal notice) of the VectolynCloud and Vectolyn Network website.'),
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'imprint', name: t('Impressum', 'Imprint') }],
      body: () => `${pageHead('', t('Impressum', 'Imprint'), '')}
<section class="band prose">
  ${draftNote(t)}
  <h2>${t('Angaben gemäß § 5 DDG', 'Information according to § 5 DDG (German Digital Services Act)')}</h2>
  <p>Tim Hauke<br>Albert-Schweitzer-Ring 42<br>25524 Itzehoe<br>${t('Deutschland', 'Germany')}</p>
  <p>${t('Privatperson, die VectolynCloud und das Vectolyn Network als nicht gewerbliches Gemeinschaftsprojekt betreibt.', 'Private individual running VectolynCloud and Vectolyn Network as a non-commercial community project.')}</p>
  <h2>${t('Kontakt', 'Contact')}</h2>
  <p>${t('E-Mail:', 'E-mail:')} ${config.contactEmail ? `<a href="mailto:${config.contactEmail}">${config.contactEmail}</a>` : `<span class="placeholder">${t('Adresse folgt mit der eigenen Domain', 'address follows with our own domain')}</span>`}</p>
  <h2>${t('Verantwortlich für den Inhalt', 'Responsible for the content')}</h2>
  <p>Tim Hauke, ${t('Anschrift wie oben', 'address as above')}</p>
</section>`,
    };
  },

  privacy(lang, config) {
    const t = L(lang);
    return {
      title: t('Datenschutzerklärung', 'Privacy policy'),
      description: t('Welche Daten beim Besuch dieser Website verarbeitet werden: Hosting bei GitHub Pages, keine Cookies, keine Analyse ohne Einwilligung.', 'What data is processed when you visit this website: hosting on GitHub Pages, no cookies, no analytics without consent.'),
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'privacy', name: t('Datenschutz', 'Privacy') }],
      body: (ctx) => `${pageHead('', t('Datenschutzerklärung', 'Privacy policy'), '')}
<section class="band prose">
  ${draftNote(t)}
  <h2>${t('Verantwortlicher', 'Controller')}</h2>
  <p>Tim Hauke, Albert-Schweitzer-Ring 42, 25524 Itzehoe. ${t('Kontakt siehe', 'Contact details in the')} <a href="${ctx.link('imprint')}">${t('Impressum', 'imprint')}</a>.</p>
  <h2>${t('Hosting', 'Hosting')}</h2>
  <p>${t('Die Website liegt bei GitHub Pages (GitHub, Inc., USA). Beim Aufruf verarbeitet GitHub technisch nötige Daten wie deine IP-Adresse, um die Seite auszuliefern und Angriffe abzuwehren. Rechtsgrundlage ist unser berechtigtes Interesse an einer sicheren, erreichbaren Website (Art. 6 Abs. 1 lit. f DSGVO). Einzelheiten:', 'The website is hosted on GitHub Pages (GitHub, Inc., USA). When you open it, GitHub processes technically necessary data such as your IP address to deliver the page and fend off attacks. The legal basis is our legitimate interest in a secure, reachable website (Art. 6(1)(f) GDPR). Details:')} ${ext('https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement', t('Datenschutzerklärung von GitHub', 'GitHub privacy statement'))}.</p>
  <h2>${t('Cookies, Schriften, Inhalte Dritter', 'Cookies, fonts, third-party content')}</h2>
  <p>${config.analytics.gtmId ? t('Ohne deine Einwilligung setzt diese Website keine Cookies.', 'Without your consent this website sets no cookies.') : t('Diese Website setzt keine Cookies.', 'This website sets no cookies.')} ${t('Schriften, Bilder, Skripte und die 3D-Vorschau kommen vom selben Server; ohne Einwilligung werden keine Inhalte von anderen Anbietern nachgeladen.', 'Fonts, images, scripts and the 3D preview come from the same server; without consent nothing is loaded from other providers.')} ${t('Stellst du Farbmodus oder Animationen um, merkt sich dein Browser diese Wahl im lokalen Speicher (localStorage). Übertragen wird dabei nichts. Das ist für die gewünschte Funktion nötig (§ 25 Abs. 2 Nr. 2 TDDDG).', 'If you change the colour mode or animations, your browser remembers that choice in local storage (localStorage). Nothing is sent anywhere. This is needed for the function you asked for (§ 25(2) no. 2 TDDDG).')}${config.testNetwork.statusUrl ? t(' Für die Statusanzeige ruft dein Browser die Statusadresse des Testnetzwerks ab; dabei sieht dieser Server deine IP-Adresse.', ' For the status display your browser queries the status address of the test network, which sees your IP address.') : ''}</p>
  <h2>${t('Statistik', 'Statistics')}</h2>
  <p>${config.analytics.gtmId
    ? t('Mit deiner Einwilligung (Art. 6 Abs. 1 lit. a DSGVO, § 25 Abs. 1 TDDDG) laden wir den Google Tag Manager und Google Analytics 4 der Google Ireland Ltd. Ohne Einwilligung wird nichts davon geladen. Deine Wahl speichern wir im lokalen Speicher deines Browsers; ändern kannst du sie jederzeit über „Statistik-Einstellungen“ unten auf jeder Seite.', 'With your consent (Art. 6(1)(a) GDPR, § 25(1) TDDDG) we load Google Tag Manager and Google Analytics 4 by Google Ireland Ltd. Without consent none of it is loaded. Your choice is kept in your browser’s local storage; you can change it any time through “Statistics settings” at the bottom of every page.')
    : t('Zurzeit gibt es keine Statistik und kein Tracking. Kommt eines dazu, dann nur mit deiner ausdrücklichen Einwilligung, und diese Erklärung wird vorher ergänzt.', 'There are no statistics and no tracking at the moment. If any is added, it will only run with your explicit consent, and this policy will be updated first.')}</p>
  <h2>${t('Deine Rechte', 'Your rights')}</h2>
  <p>${t('Du hast das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung der Verarbeitung, Datenübertragbarkeit und Widerspruch (Art. 15 bis 21 DSGVO). Außerdem kannst du dich bei einer Aufsichtsbehörde beschweren, zum Beispiel beim Unabhängigen Landeszentrum für Datenschutz Schleswig-Holstein.', 'You have the right of access, rectification, erasure, restriction of processing, data portability and objection (Art. 15 to 21 GDPR). You can also complain to a supervisory authority, for example the Independent Centre for Privacy Protection Schleswig-Holstein (ULD).')}</p>
</section>`,
    };
  },
};

function ctx_api() { return '@api'; }

export const extraPages = {
  root(lang) {
    return {
      title: 'VectolynCloud: Minecraft-Netzwerk selbst betreiben / Run your own Minecraft network',
      description: 'VectolynCloud: freie Software für dein eigenes Minecraft-Netzwerk. Free software to run your own Minecraft network. Deutsch oder English.',
      body: (ctx) => `<section class="page-head lang-pick">
  <h1>VectolynCloud</h1>
  <p class="lede"><span lang="de">Sprache wählen</span> / Choose your language</p>
  <p class="actions"><a class="btn btn-primary" href="${ctx.link('home').replace('en/', 'de/')}" hreflang="de" lang="de">Deutsch</a> <a class="btn btn-primary" href="${ctx.link('home')}" hreflang="en">English</a></p>
</section>`,
    };
  },
  notFound(lang) {
    return {
      title: 'Page not found / Seite nicht gefunden',
      description: 'This page does not exist. Diese Seite gibt es nicht.',
      noindex: true,
      body: (ctx) => `<section class="page-head">
  <h1>404</h1>
  <p class="lede">This page does not exist. <span lang="de">Diese Seite gibt es nicht.</span></p>
  <p class="actions"><a class="btn btn-primary" href="${ctx.link('home')}">English home</a> <a class="btn" href="${ctx.link('home').replace('en/', 'de/')}" lang="de">Deutsche Startseite</a></p>
</section>`,
    };
  },
};

// ---------- Blog ----------
const fmtDate = (lang, d) => (lang === 'de' ? dateDeShort(d) : dateEnShort(d));
function dateDeShort(d) { const [y, m, day] = d.split('-'); return `${Number(day)}.${Number(m)}.${y}`; }
function dateEnShort(d) { const [y, m, day] = d.split('-').map(Number); return `${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][m - 1]} ${day}, ${y}`; }
const postKey = (p) => `post-${p.slug.en}`;
for (const p of posts) routes[postKey(p)] = { de: `de/blog/${p.slug.de}/`, en: `en/blog/${p.slug.en}/` };

pages.blog = (lang) => {
  const t = L(lang);
  return {
    title: t('Neuigkeiten zu VectolynCloud', 'VectolynCloud news'),
    description: t('Neue Versionen, Änderungen an der Website und Neuigkeiten zum Testnetzwerk, mit Datum und Autor.', 'New versions, changes to the website and news about the test network, with date and author.'),
    type: 'Blog',
    crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'blog', name: t('Neuigkeiten', 'News') }],
    body: (ctx) => `${pageHead('', t('Neuigkeiten', 'News'), t('Neue Versionen und was sich am Projekt ändert. Als Feed:', 'New versions and what changes in the project. As a feed:') + ` <a href="${ctx.asset(lang + '/blog/feed.xml')}">Atom</a>.`)}
<section class="band">
  <ol class="posts">${posts.map((p) => `<li class="post-card"><article><p class="post-meta"><time datetime="${p.date}">${fmtDate(lang, p.date)}</time>, ${p.author}</p><h2><a href="${ctx.link(postKey(p))}">${p.title[lang]}</a></h2><p>${p.summary[lang]}</p></article></li>`).join('')}</ol>
</section>`,
  };
};
for (const p of posts) {
  pages[postKey(p)] = (lang) => {
    const t = L(lang);
    return {
      title: `${p.title[lang]} | VectolynCloud`,
      description: p.summary[lang],
      type: 'WebPage',
      section: 'blog',
      crumbs: [{ key: 'home', name: 'VectolynCloud' }, { key: 'blog', name: t('Neuigkeiten', 'News') }, { key: postKey(p), name: p.title[lang] }],
      extraLd: (base) => [{ '@type': 'BlogPosting', headline: p.title[lang], description: p.summary[lang], datePublished: p.date, dateModified: p.date, inLanguage: lang,
        author: { '@type': 'Person', name: p.author, url: 'https://github.com/VectoDE' }, publisher: { '@id': base + '#org' }, mainEntityOfPage: base + routes[postKey(p)][lang] }],
      body: (ctx) => `<article class="post">
${pageHead('', p.title[lang], '')}
<section class="band prose">
  <p class="post-meta">${t('Veröffentlicht am', 'Published')} <time datetime="${p.date}">${fmtDate(lang, p.date)}</time> ${t('von', 'by')} ${p.author}</p>
  ${p.body[lang]}
  <p><a class="text-link" href="${ctx.link('blog')}">${t('Alle Neuigkeiten', 'All news')}</a></p>
</section>
</article>`,
    };
  };
}

export { posts };
