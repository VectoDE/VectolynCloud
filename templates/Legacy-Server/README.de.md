# Vorlage Legacy-Server (1.8.8)

[English](README.md)

Backend für Gruppen mit Minecraft 1.8.8, etwa ein 1.8-PvP-Minigame. Software ist PandaSpigot (Gruppeneinstellung `software: pandaspigot`, `minecraft-version: "1.8.8"`), das unter Java 21 läuft (siehe [docs/cloud/legacy-1.8.8.md](../../docs/cloud/legacy-1.8.8.md)). Diese Server stehen hinter dem [Legacy-Proxy](../Legacy-Proxy/README.de.md), nie hinter dem Hauptproxy.

Die Vorlage enthält nur, was Vectora braucht. Alle übrigen Einstellungen ergänzt PandaSpigot beim ersten Start mit seinen Standardwerten.

- `server.properties`: `online-mode=false`, gebunden an `VECTORA_SERVER_HOST`. Das sollte eine Loopback- oder private Adresse bleiben.
- `spigot.yml`: `settings.bungeecord: true`, damit der Server das Forwarding des Legacy-Proxys annimmt.
- `spigot.yml`: `settings.restart-script: ''`, damit weder `/restart` noch ein Watchdog-Absturz einen Server außerhalb der Cloud startet; beide halten ihn nur an. `settings.restart-on-crash: true`, weil der 1.8.8-Watchdog bei `false` einen hängenden Server weiterlaufen lässt, statt ihn zu beenden. Neustarts übernimmt der Node; er setzt beide Werte auch selbst.
- `pandaspigot.yml`: `proxyOnlineMode: true`, passend zu `online-mode = true` des Legacy-Proxys.

PandaSpigot prüft den `bungeeguard-token` nicht selbst. Bis die Vectora-Bridge das übernimmt (Etappe 2, PR 13), kann jeder, der einen Backend-Port erreicht, mit beliebigem Namen beitreten. Die Ports müssen deshalb nach außen geschlossen bleiben.

## Platzhalter

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}` in `server.properties`
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Noch keine Datenbank-Platzhalter: Rangdaten auf 1.8.8 kommen mit dem Vectora-Plugin (Etappe 3).

## Gewollt oder nicht

Nur als gewollt markierte Gruppen rendert die Cloud aus dieser Vorlage nach `runtime/` und startet sie. Diese README ist reine Doku, die Cloud kopiert sie nie nach `runtime/`. Alle Platzhalter: [`../placeholders.md`](../placeholders.md).
