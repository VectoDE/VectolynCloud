# Vorlage Minigame-Server

[English](README.md)

Eine Vorlage für alle Minigame-Gruppen, bis Etappe 6 die einzelnen Spiele bringt (BedWars, SkyWars und die übrigen). Die Cloud startet daraus so viele Dienste, wie die Skalierung der Gruppe erlaubt, jeden auf einem eigenen Port.

Software: Paper auf der neuesten Minecraft-Version (derzeit 26.3), derselbe Build wie bei den anderen Backends. Plugins derzeit: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (die letzten fünf ersetzen spätere Etappen durch Vectolyn-Module).

Unterschiede zur Lobby: `gamemode=survival` und `spawn-protection=0`, weil die Spiele Spielmodus und Schutzzone selbst setzen. Kein `VectolynLobby`.

## Platzhalter

- `{{VECTOLYN_SERVER_PORT}}`, `{{VECTOLYN_SERVER_HOST}}` in `server.properties`
- `{{VECTOLYN_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTOLYN_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTOLYN_DB_HOST}}`, `{{VECTOLYN_DB_PORT}}`, `{{VECTOLYN_DB_NAME}}`, `{{VECTOLYN_DB_USER}}`, `{{VECTOLYN_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTOLYN_DB_STORAGE_METHOD}}` und `{{VECTOLYN_DB_POOL_SIZE}}` in `plugins/LuckPerms/config.yml`. Minigame-Gruppen laufen mit vielen Diensten gleichzeitig. Würde die Summe der Pools das Verbindungslimit der Datenbank überschreiten, verkleinert die Cloud den Pool je Dienst.
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- `{{VECTOLYN_TAB_POWERED_BY}}` in `plugins/TAB/config.yml`: die letzte Fußzeile „Powered by VectolynCloud“, solange `branding.powered-by` in `cloud.yml` true ist (Standard), sonst nichts
- Erzeugte Datei: `plugins/floodgate/key.pem` aus `VECTOLYN_FLOODGATE_KEY_B64`

## Gewollt oder nicht

Der Setup-Assistent fragt für jede Minigame-Gruppe, ob sie gewollt ist. Nur gewollte Gruppen rendert die Cloud aus dieser Vorlage in einen eigenen Ordner unter `local/services/` und startet sie, und nur sie erscheinen in der Serverliste des Proxys. Diese README ist reine Doku, die Cloud kopiert sie nie in einen Dienst-Ordner. Alle Platzhalter: [`../placeholders.md`](../placeholders.md).
