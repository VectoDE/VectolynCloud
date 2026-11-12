# Funktionsstand

[English](Feature-Status)

Was es gibt, was es teilweise gibt und was nur geplant ist, gemessen an der Zielliste des Projekts. „Gebaut“ heißt: Der Code liegt im Repository und hat Tests; es heißt nicht produktionsreif, denn es gibt noch kein stabiles Release. Geplante Punkte verlinken das Issue, das sie verfolgt. Die Tabelle wird in dem Pull Request aktualisiert, der den Status ändert.

Die Zielliste ist die eigene Spezifikation des Projekts. Eine ausführlichere Matrix mit jeder einzelnen Funktion entsteht in [#132](https://github.com/VectoDE/VectolynCloud/issues/132); diese Seite verlinkt sie dann.

Stand: Pre-Release [v0.1.1-dev](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.1-dev) und der Branch `main` danach.

## Start und Installation

| Funktion | Status | Hinweise |
|---|---|---|
| Start mit `java -jar VectolynCloud.jar` | Gebaut | [Erste Schritte](Getting-Started-DE) |
| Einrichtungsassistent in der Konsole, Enter übernimmt Standardwerte | Gebaut | [Einrichtung](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.de.md) |
| Setup-Datei für unbeaufsichtigte Installationen | Gebaut | `--setup-file` |
| Panel-Admin, angelegt mit einem einmaligen Setup-Token | Geplant | [#125](https://github.com/VectoDE/VectolynCloud/issues/125) |
| Neueste stabile Minecraft-Version als Standard, ältere nach Wahl | Geplant | [#125](https://github.com/VectoDE/VectolynCloud/issues/125) |
| Startskripte für Windows, Linux, macOS | Gebaut | in jedem Release |
| Docker-Image auf GHCR (amd64, arm64), compose-Datei | Gebaut | Tags `0.1.1-dev` und `dev`; SBOM geplant in [#221](https://github.com/VectoDE/VectolynCloud/issues/221) |
| Pterodactyl-Egg | Gebaut | noch nicht unter einem echten Wings getestet, siehe [#11](https://github.com/VectoDE/VectolynCloud/issues/11) |
| Pelican-Egg | Geplant | [#216](https://github.com/VectoDE/VectolynCloud/issues/216) |
| systemd-Unit | Gebaut | Installationsskript geplant in [#217](https://github.com/VectoDE/VectolynCloud/issues/217) |
| Signierte Releases mit Prüfsummen und Provenance | Gebaut | cosign und GitHub-Attestierung, siehe [Betrieb](Operations-DE) |

## Cloud-Kern

| Funktion | Status | Hinweise |
|---|---|---|
| Konsole mit Hilfe und Tab-Vervollständigung | Gebaut | Abnahme offen in [#126](https://github.com/VectoDE/VectolynCloud/issues/126) |
| Gruppen, Tasks, statische und dynamische Dienste | Gebaut | [Konfiguration](Configuration-DE) |
| Auswahl der gewollten Server, nur diese werden angelegt | Gebaut | |
| Autoskalierung nach Spielerzahl und Gruppenlast | Gebaut | CPU, RAM, TPS, Zeitpläne und eine Warteschlange sind geplant in [#128](https://github.com/VectoDE/VectolynCloud/issues/128) |
| Mehrere Nodes (Cluster) | Teilweise | gegenseitiges TLS, Beitritts-Tokens, Versionsprüfung, Template-Spiegel; Platzierung, Failover und Verschieben von Diensten sind geplant in [#73](https://github.com/VectoDE/VectolynCloud/issues/73) und [#127](https://github.com/VectoDE/VectolynCloud/issues/127) |
| YAML-Konfiguration mit strenger Prüfung, Neuladen | Gebaut | |
| JSON Schema für Editoren, Import und Export, Änderungsvorschau | Geplant | [#129](https://github.com/VectoDE/VectolynCloud/issues/129) |
| Datenbanken: verwaltete MariaDB, Docker, externe MariaDB/MySQL, H2 | Gebaut | PostgreSQL geplant in [#129](https://github.com/VectoDE/VectolynCloud/issues/129) |
| Download der Server-Software mit Prüfsummen und Lock-Datei | Gebaut | Testlauf nach Updates und Rollback geplant in [#77](https://github.com/VectoDE/VectolynCloud/issues/77) |
| Verwaltung der Java-Laufzeiten | Gebaut | Adoptium, je Gruppe |
| Eigener Messaging-Kanal und Bridges (Velocity, Paper, PandaSpigot 1.8.8) | Gebaut | |
| Weitere Server-Software (Folia, Spigot, BungeeCord, Forge, NeoForge, Minestom, Sponge) und Bridges für Nukkit und WaterdogPE | Geplant | [#134](https://github.com/VectoDE/VectolynCloud/issues/134), [#135](https://github.com/VectoDE/VectolynCloud/issues/135) |
| Verwalteter Legacy-Proxy für 1.8.8 | Teilweise | [#78](https://github.com/VectoDE/VectolynCloud/issues/78) |
| Updates: Migrationen, signiertes Selbst-Update, Wartungsmodus, Rollback | Geplant | [#74](https://github.com/VectoDE/VectolynCloud/issues/74) bis [#77](https://github.com/VectoDE/VectolynCloud/issues/77), [#130](https://github.com/VectoDE/VectolynCloud/issues/130) |
| Rotierende Logs mit einstellbarer Aufbewahrung | Teilweise | die letzten fünf Logs der Cloud bleiben erhalten; einstellbare Aufbewahrung geplant in [#130](https://github.com/VectoDE/VectolynCloud/issues/130) |
| Modulsystem mit stabiler API | Geplant | [#162](https://github.com/VectoDE/VectolynCloud/issues/162) |

## Betrieb

| Funktion | Status | Hinweise |
|---|---|---|
| Backups von Welten und Datenbank, Zeitplan, Aufbewahrung, Wiederherstellung | Gebaut | [Backups](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/backup.md) (Englisch) |
| S3-kompatibler Backup-Speicher | Geplant | [#205](https://github.com/VectoDE/VectolynCloud/issues/205) |
| Wiederherstellung im Panel | Geplant | [#206](https://github.com/VectoDE/VectolynCloud/issues/206) |
| Metriken je Dienst und Node, Warnungen, Webhooks, Prometheus-Endpunkt | Gebaut | [Monitoring](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/monitor.md) (Englisch) |
| Log-Suche | Geplant | [#212](https://github.com/VectoDE/VectolynCloud/issues/212) |

## API und Panel

| Funktion | Status | Hinweise |
|---|---|---|
| REST-API und WebSocket-Ereignisse, Tokens mit Scopes, OpenAPI-Datei | Gebaut | [API](API-DE) |
| gRPC | Geplant | [#154](https://github.com/VectoDE/VectolynCloud/issues/154) |
| GraphQL | Geplant | [#155](https://github.com/VectoDE/VectolynCloud/issues/155) |
| Rollen und API-Schlüssel | Geplant | [#156](https://github.com/VectoDE/VectolynCloud/issues/156) |
| Web-Panel mit Logins, Dateimanager, Editoren | Geplant | [#151](https://github.com/VectoDE/VectolynCloud/issues/151) |

## Netzwerk und Inhalte

| Funktion | Status | Hinweise |
|---|---|---|
| Java 1.8 bis 26.3 und Bedrock auf einem Proxy (Velocity, Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind) | Gebaut | Beitrittstests mit echten Clients noch offen in [#5](https://github.com/VectoDE/VectolynCloud/issues/5), siehe [Crossplay](Crossplay-DE) |
| Keine Command Blocks, Rechte-Audit für Staff-Befehle | Gebaut | |
| Eigener Ersatz für PlaceholderAPI, LuckPerms, TAB | Geplant | [#167](https://github.com/VectoDE/VectolynCloud/issues/167) |
| Lobby, Survival, Creative, End, Mod-Server, Minigames, Soziales | Geplant | [#176](https://github.com/VectoDE/VectolynCloud/issues/176); heute gibt es nur kleine Lobby- und Portal-Plugins |
| Moderation und Tickets | Geplant | [#182](https://github.com/VectoDE/VectolynCloud/issues/182) |
| Discord-Bot | Geplant | [#188](https://github.com/VectoDE/VectolynCloud/issues/188) |
| Website und Store | Geplant | [#194](https://github.com/VectoDE/VectolynCloud/issues/194) |
| Anti-Cheat | Geplant | [#199](https://github.com/VectoDE/VectolynCloud/issues/199) |
| Resourcepack | Geplant | [#203](https://github.com/VectoDE/VectolynCloud/issues/203) |
| Datenschutzfunktionen (Aufbewahrung, Einwilligung, Statistiken) | Geplant | [#231](https://github.com/VectoDE/VectolynCloud/issues/231) |
| Übersetzungen und Barrierefreiheit für Panel und Website | Geplant | [#235](https://github.com/VectoDE/VectolynCloud/issues/235) |

## Qualität und Vertrauen

Gebaut und im Repository sichtbar: CI auf Linux und Windows, Tests auf Java 21 und 25, End-to-End-Tests mit der echten Jar, Fuzz-Tests, CodeQL, Dependency Review, OpenSSF Scorecard und das OpenSSF-Best-Practices-Badge (Stufe *passing*), signierte Releases mit Provenance, SHA-256-Prüfung jeder Abhängigkeit. Was die Scorecard mit einem einzigen Maintainer nicht erreichen kann, und warum: [docs/openssf.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/openssf.md) (Englisch).
