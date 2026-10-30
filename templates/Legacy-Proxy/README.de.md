# Vorlage Legacy-Proxy

[English](README.md)

Zweiter Velocity-Proxy vor den 1.8.8-Gruppen (PandaSpigot, Vorlage [`Legacy-Server`](../Legacy-Server/README.de.md)). Er läuft mit `player-info-forwarding-mode = "bungeeguard"`, weil 1.8.8-Server nur den alten BungeeCord-Handshake verstehen. Der Hauptproxy bleibt auf `modern`. Hintergrund: [docs/cloud/legacy-1.8.8.md](../../docs/cloud/legacy-1.8.8.md).

Die Cloud startet ihn nur, wenn in `cloud.yml` `legacy-proxy.enabled` auf `true` steht. Er lauscht auf einem eigenen Port (`legacy-proxy.port`, Standard 25564).

Software: Velocity in derselben Version wie der Hauptproxy, noch ohne Plugins. Ob Bedrock-Spieler die 1.8.8-Gruppen über Geyser, Floodgate und Via* auf diesem Proxy erreichen, prüft Etappe 2 in PR 24.

## Platzhalter

- `{{VECTOLYN_PROXY_BIND}}`, `{{VECTOLYN_LEGACY_PROXY_PORT}}` in `velocity.toml` (Bind-Adresse und Query-Port)
- `{{VECTOLYN_PROXY_SERVERS}}`, `{{VECTOLYN_PROXY_TRY}}`, `{{VECTOLYN_PROXY_FORCED_HOSTS}}` in `velocity.toml`: Die Cloud trägt hier nur die gewollten 1.8.8-Gruppen ein, nie die Backends des Hauptproxys.
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- `server-icon.png` (64 x 64, das Vectolyn-Zeichen) wird unverändert kopiert; Velocity zeigt es in der Serverliste
- Erzeugte Datei: `forwarding.secret`. Die Cloud schreibt hier `VECTOLYN_LEGACY_FORWARDING_SECRET` hinein, nie `VECTOLYN_FORWARDING_SECRET` des Hauptproxys. Velocity schickt es als `bungeeguard-token` im Klartext im Handshake an die Backends. Deshalb darf es nie dem Modern-Forwarding-Secret des Hauptproxys entsprechen.

Diese README ist reine Doku, die Cloud kopiert sie nie in einen Dienst-Ordner. Alle Platzhalter: [`../placeholders.md`](../placeholders.md).
