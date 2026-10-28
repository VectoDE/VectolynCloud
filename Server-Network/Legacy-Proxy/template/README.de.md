# Vorlage Legacy-Proxy

[English](README.md)

Zweiter Velocity-Proxy vor den 1.8.8-Gruppen (PandaSpigot, Vorlage [`Legacy-Server`](../../Legacy-Server/template/README.de.md)). Er läuft mit `player-info-forwarding-mode = "bungeeguard"`, weil 1.8.8-Server nur den alten BungeeCord-Handshake verstehen. Der Hauptproxy bleibt auf `modern`. Hintergrund: [docs/cloud/legacy-1.8.8.md](../../../docs/cloud/legacy-1.8.8.md).

Die Cloud startet ihn nur, wenn in `cloud.yml` `legacy-proxy.enabled` auf `true` steht. Er lauscht auf einem eigenen Port (`legacy-proxy.port`, Standard 25564).

Software: Velocity in derselben Version wie der Hauptproxy, noch ohne Plugins. Ob Bedrock-Spieler die 1.8.8-Gruppen über Geyser, Floodgate und Via* auf diesem Proxy erreichen, prüft Etappe 2 in PR 24.

## Platzhalter

- `{{VECTORA_PROXY_BIND}}`, `{{VECTORA_LEGACY_PROXY_PORT}}` in `velocity.toml` (Bind-Adresse und Query-Port)
- `{{VECTORA_PROXY_SERVERS}}`, `{{VECTORA_PROXY_TRY}}`, `{{VECTORA_PROXY_FORCED_HOSTS}}` in `velocity.toml`: Die Cloud trägt hier nur die gewollten 1.8.8-Gruppen ein, nie die Backends des Hauptproxys.
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Erzeugte Datei: `forwarding.secret`. Die Cloud schreibt hier `VECTORA_LEGACY_FORWARDING_SECRET` hinein, nie `VECTORA_FORWARDING_SECRET` des Hauptproxys. Velocity schickt es als `bungeeguard-token` im Klartext im Handshake an die Backends. Deshalb darf es nie dem Modern-Forwarding-Secret des Hauptproxys entsprechen.

Diese README ist reine Doku, die Cloud kopiert sie nie nach `runtime/`. Alle Platzhalter: [`../../placeholders.md`](../../placeholders.md).
