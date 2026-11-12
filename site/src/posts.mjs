// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Blog posts, newest first. Each post has a date, an author and the same text in German and English.
// Facts in a post must be backed by the repository (release notes, merged pull requests).

const REPO = 'https://github.com/VectoDE/VectolynCloud';

export const posts = [
  {
    slug: { de: 'neue-website', en: 'new-website' },
    date: '2026-11-12',
    author: 'Tim Hauke',
    title: { de: 'Die Website ist neu', en: 'A new website' },
    summary: {
      de: 'VectolynCloud hat eine eigene Website auf Deutsch und Englisch, mit Download, Dokumentation, Testnetzwerk und Shop im Testmodus.',
      en: 'VectolynCloud has its own website in German and English, with download, documentation, the test network and a shop in test mode.',
    },
    body: {
      de: `<p>Bisher gab es eine einzelne Startseite. Jetzt hat das Projekt eine richtige Website, in Deutsch und Englisch, jede Sprache unter eigener Adresse.</p>
<p>Sie ist in zwei Teile gegliedert. Der erste gehört der Software: Was VectolynCloud macht, wo du sie herunterlädst, wie du die Signaturen prüfst und wo die Dokumentation liegt. Der zweite gehört dem Vectolyn Network, unserem geplanten Testnetzwerk. Dort stehen schon die Regeln, das Team und der Shop. Das Netzwerk selbst ist noch nicht online, und der Shop läuft nur im Testmodus.</p>
<p>Die Seite ist ohne Framework gebaut und lädt nichts von fremden Servern. Schriften und die 3D-Vorschau liegen im Repository. Es gibt kein Tracking; sollte später eine Statistik dazukommen, dann nur mit deiner Einwilligung.</p>
<p>Der Quelltext liegt wie alles andere in <a href="${REPO}/tree/main/site">site/</a>. Fehler und Wünsche nehmen wir gern in den <a href="${REPO}/discussions">Diskussionen</a> entgegen.</p>`,
      en: `<p>Until now there was a single start page. The project now has a proper website in German and English, each language at its own address.</p>
<p>It has two parts. The first is about the software: what VectolynCloud does, where to download it, how to check the signatures and where the documentation lives. The second belongs to Vectolyn Network, our planned test network. Its rules, team and shop are already there. The network itself is not online yet, and the shop runs in test mode only.</p>
<p>The site is built without a framework and loads nothing from other servers. Fonts and the 3D preview are part of the repository. There is no tracking; if statistics are added later, they will only run with your consent.</p>
<p>Like everything else, the source is in <a href="${REPO}/tree/main/site">site/</a>. Bugs and ideas are welcome in the <a href="${REPO}/discussions">discussions</a>.</p>`,
    },
  },
  {
    slug: { de: 'v0-1-2-dev', en: 'v0-1-2-dev' },
    date: '2026-11-12',
    author: 'Tim Hauke',
    title: { de: 'Vorabversion 0.1.2-dev: Cluster, Panel, PostgreSQL', en: 'Pre-release 0.1.2-dev: cluster, panel, PostgreSQL' },
    summary: {
      de: 'Die zweite Vorabversion bringt Cluster-Knoten, die ausgefallene Mitglieder erkennen, ein Web-Panel mit Konten und Rollen und einen Adapter für PostgreSQL.',
      en: 'The second pre-release brings cluster nodes that notice lost members, a web panel with accounts and roles, and a PostgreSQL adapter.',
    },
    body: {
      de: `<p>Version 0.1.2-dev ist eine Vorabversion zum Ausprobieren, nicht für den Echtbetrieb. Die wichtigsten Neuerungen:</p>
<ul class="ticks">
<li>Mehrere Knoten: Mitglieder melden ihren Zustand, der Kopf wählt den Knoten für einen Dienst und plant die Dienste eines ausgefallenen Mitglieds auf den anderen ein.</li>
<li>Skalierung nach CPU, TPS und Heap, mit Zeitplänen und Skalierungsentscheidungen als Ereignisse.</li>
<li>Web-Panel im Jar: Konten mit Passwort, TOTP, Passkeys und Single Sign-on, Rollen und ein Audit-Log.</li>
<li>systemd: <code>install.sh</code> installiert, aktualisiert und entfernt VectolynCloud. Dazu ein Pelican-Egg.</li>
<li>Datenbank: externer PostgreSQL-Adapter.</li>
<li>API: GraphQL mit Rechten je Feld, gRPC auf eigenem Port, ablaufende API-Schlüssel.</li>
<li>Datenschutz: Statistik je Spielmodus und Telemetrie, die ohne Einwilligung aus bleibt; IP-Sperrliste mit einstellbarer Aufbewahrung.</li>
</ul>
<p>Alle Änderungen, die Dateien und die Signaturen stehen in den <a href="${REPO}/releases/tag/v0.1.2-dev">Release-Notizen auf GitHub</a>.</p>`,
      en: `<p>Version 0.1.2-dev is a pre-release for trying out, not for production. The main changes:</p>
<ul class="ticks">
<li>Several nodes: members report their status, the head picks the node for a service and plans the services of a lost member on the others.</li>
<li>Scaling on CPU, TPS and heap, with schedules and scaling decisions as events.</li>
<li>A web panel inside the jar: accounts with password, TOTP, passkeys and single sign-on, roles and an audit log.</li>
<li>systemd: <code>install.sh</code> installs, updates and removes VectolynCloud. Plus a Pelican egg.</li>
<li>Database: an external PostgreSQL adapter.</li>
<li>API: GraphQL with a scope per field, gRPC on a port of its own, expiring API keys.</li>
<li>Privacy: statistics per game mode and telemetry that stays off without consent; an IP ban list with configurable retention.</li>
</ul>
<p>Every change, the files and the signatures are in the <a href="${REPO}/releases/tag/v0.1.2-dev">release notes on GitHub</a>.</p>`,
    },
  },
  {
    slug: { de: 'v0-1-1-dev', en: 'v0-1-1-dev' },
    date: '2026-10-30',
    author: 'Tim Hauke',
    title: { de: 'Vorabversion 0.1.1-dev: Marke, Cluster-Spiegel, Release-Regeln', en: 'Pre-release 0.1.1-dev: brand, cluster mirror, release rules' },
    summary: {
      de: 'Die erste signierte Vorabversion mit Start-Skripten für Windows, Linux und macOS, der neuen Marke und gespiegelter Konfiguration im Cluster.',
      en: 'The first signed pre-release with start scripts for Windows, Linux and macOS, the new brand and mirrored configuration in a cluster.',
    },
    body: {
      de: `<p>Version 0.1.1-dev enthält <code>VectolynCloud.jar</code>, die Start-Skripte für Windows und für Linux und macOS sowie das Release-Banner. Jede Datei steht in <code>SHA256SUMS</code>, ist mit cosign signiert und hat eine Herkunftsbescheinigung.</p>
<ul class="ticks">
<li>Marke: Zeichen, Wortmarke und Markenhandbuch, neuer Konsolen-Banner, neue MOTDs und ein Server-Icon auf beiden Proxys.</li>
<li>Cluster: Mitglieder übernehmen Knoten, Gruppen und Aufgaben vom Kopf; Vorlagen werden geprüft übertragen und atomar getauscht.</li>
<li>Releases: Versionsregeln, die vor dem Bauen und Signieren geprüft werden.</li>
<li>Container: Das Tag <code>dev</code> zeigt immer auf die neueste Entwicklerversion.</li>
</ul>
<p>Details in den <a href="${REPO}/releases/tag/v0.1.1-dev">Release-Notizen</a>.</p>`,
      en: `<p>Version 0.1.1-dev contains <code>VectolynCloud.jar</code>, the start scripts for Windows and for Linux and macOS, and the release banner. Every file is listed in <code>SHA256SUMS</code>, signed with cosign and covered by build provenance.</p>
<ul class="ticks">
<li>Brand: mark, wordmark and brand guide, a new console banner, new MOTDs and a server icon on both proxies.</li>
<li>Cluster: members take nodes, groups and tasks from the head; templates are transferred with checks and swapped in atomically.</li>
<li>Releases: version rules that are checked before anything is built or signed.</li>
<li>Container: the <code>dev</code> tag always points to the newest development release.</li>
</ul>
<p>Details in the <a href="${REPO}/releases/tag/v0.1.1-dev">release notes</a>.</p>`,
    },
  },
];
