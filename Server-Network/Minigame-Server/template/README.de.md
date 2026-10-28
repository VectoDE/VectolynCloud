# Vorlage Minigame-Server

[English](README.md)

Eine Vorlage für alle Minigame-Gruppen, bis Etappe 6 die einzelnen Spiele bringt (BedWars, SkyWars und die übrigen). Die Cloud startet daraus so viele Dienste, wie die Skalierung der Gruppe erlaubt, jeden auf einem eigenen Port.

Software: Paper auf der neuesten Minecraft-Version (derzeit 26.3), derselbe Build wie bei den anderen Backends. Plugins derzeit: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (die letzten fünf ersetzen spätere Etappen durch Vectora-Module).

Unterschiede zur Lobby: `gamemode=survival` und `spawn-protection=0`, weil die Spiele Spielmodus und Schutzzone selbst setzen. Kein `VectoraLobby`.

## Platzhalter

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}` in `server.properties`
- `{{VECTORA_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTORA_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTORA_DB_STORAGE_METHOD}}` und `{{VECTORA_DB_POOL_SIZE}}` in `plugins/LuckPerms/config.yml`. Minigame-Gruppen laufen mit vielen Diensten gleichzeitig. Würde die Summe der Pools das Verbindungslimit der Datenbank überschreiten, verkleinert die Cloud den Pool je Dienst.
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Erzeugte Datei: `plugins/floodgate/key.pem` aus `VECTORA_FLOODGATE_KEY_B64`

## Gewollt oder nicht

Der Setup-Assistent fragt für jede Minigame-Gruppe, ob sie gewollt ist. Nur gewollte Gruppen rendert die Cloud aus dieser Vorlage nach `runtime/` und startet sie, und nur sie erscheinen in der Serverliste des Proxys. Diese README ist reine Doku, die Cloud kopiert sie nie nach `runtime/`. Alle Platzhalter: [`../../placeholders.md`](../../placeholders.md).
