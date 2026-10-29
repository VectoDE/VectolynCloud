# Vectora Network

[![CI](https://github.com/VectoDE/Vectora-Network/actions/workflows/ci.yml/badge.svg)](https://github.com/VectoDE/Vectora-Network/actions/workflows/ci.yml)
[![CodeQL](https://github.com/VectoDE/Vectora-Network/actions/workflows/codeql.yml/badge.svg)](https://github.com/VectoDE/Vectora-Network/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/VectoDE/Vectora-Network/badge)](https://scorecard.dev/viewer/?uri=github.com/VectoDE/Vectora-Network)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16026/badge)](https://www.bestpractices.dev/projects/16026)
[![Lizenz: GPL-3.0](https://img.shields.io/github/license/VectoDE/Vectora-Network)](LICENSE)

[English](README.md) · **Deutsch**

Vectora ist ein Open-Source-Baukasten (GPL-3.0), mit dem du dein eigenes Minecraft-Netzwerk betreibst. Darauf spielen Java-Spieler (1.8 bis 26.3) und Bedrock-Spieler zusammen.

Wir selbst betreiben kein Netzwerk: Es gibt keinen offiziellen Server, keine Domain und keine gehostete Website, keinen Discord-Bot und keinen Store. Website, Discord-Bot und Store liegen als Code in diesem Repository. Du konfigurierst sie mit deinem eigenen Bot-Token und deinem eigenen Stripe-Konto und hostest sie selbst.

Das langfristige Ziel: Du startest `java -jar VectoraCloud.jar` unter Windows, Linux, macOS, in Docker oder auf Pterodactyl, beantwortest einen Einrichtungsassistenten, und danach läuft das komplette Netzwerk mit Datenbank, Proxy, Lobby, Spielmodi und Minigames. Ein Release wird `VectoraCloud.jar`, Startskripte für Windows, Linux und macOS und ein Docker-Image auf GHCR enthalten, alles signiert und mit Provenance. Plugin-Jars hängen nicht am Release. Dockerfile, compose-Datei und Pterodactyl-Egg liegen in diesem Repository.

## Stand

**Etappe 2 von 8: VectoraCloud-Kern.** Etappe 1, das Fundament, ist fertig und als signiertes Pre-Release [v0.1.0](https://github.com/VectoDE/Vectora-Network/releases/tag/v0.1.0) erschienen. In Etappe 2 entsteht die Cloud Modul für Modul; die Aufgabenliste steht in [#11](https://github.com/VectoDE/Vectora-Network/issues/11), der Gesamtplan auf der Roadmap. Ein nutzbares Cloud-Release gibt es noch nicht, v0.1.0 enthält nur das Fundament. Vieles unter *Architektur* ist noch Plan, kein fertiger Code.

- Roadmap und Fortschritt: [Vectora Roadmap](https://github.com/users/VectoDE/projects/11)
- Aktuelle Etappe: [#11 Stage 2: VectoraCloud core](https://github.com/VectoDE/Vectora-Network/issues/11)

| Etappe | Inhalt |
|---|---|
| 1 | Fundament: Repository, Build, Konfigurationsfehler |
| 2 | VectoraCloud-Kern |
| 3 | Vectora-Plugin-Basis: Core, Rechte, Messaging, Portale/End, Sync, Versionsadapter |
| 4 | Lobby |
| 5 | Survival, Creative, Mod-Server |
| 6 | Minigames und Anti-Cheat |
| 7 | Marke und Resourcepack |
| 8 | Dashboard, Discord-Bot, Website, Store |

## Architektur

```
 Java 1.8 bis 26.3        Bedrock
        │                    │
        ▼                    ▼
 ┌──────────────────────────────────────┐
 │ Proxy: Velocity                      │
 │ ViaVersion/ViaBackwards/ViaRewind    │
 │ Geyser + Floodgate                   │
 └──────────────────┬───────────────────┘
                    │ Modern Forwarding
   ┌────────┬───────┼─────────┬─────────┬───────────┐
   ▼        ▼       ▼         ▼         ▼           ▼
 Lobby  Survival Creative  Mod (Fabric) End    Minigames (geplant)
   └────────┴───────┴─────────┴─────────┴───────────┘
                    │
                MariaDB

 VectoraCloud (geplant) startet, skaliert und überwacht alles oben.
```

- **Proxy:** Velocity ist der einzige Einstieg. Geyser und Floodgate lassen Bedrock-Clients herein, die Via-Plugins übersetzen ältere Java-Versionen. Die Backends laufen auf der neuesten Minecraft-Version.
- **Backends:** Paper für Lobby, Survival, Creative, End und später die Minigames. Fabric für den Mod-Server.
- **VectoraCloud** (geplant, Etappe 2): ein Java-Node mit Konsole und Einrichtungsassistent. Er lädt Server-Software herunter und prüft sie, verwaltet JREs und Datenbank, erzeugt Secrets, skaliert Minigame-Server und bietet eine REST-/WebSocket-API. Die Einrichtung läuft wie bei CloudNet: Assistent in der Konsole beim ersten Start, danach Konfigurationsdateien und Konsolenbefehle (Etappe 2). Später soll das Web-Dashboard das alles auch können, einschließlich der Ersteinrichtung (Etappe 8). Im Assistenten, in der Konfiguration oder später im Dashboard markierst du jeden der sieben Server und jede Minigame-Gruppe als gewollt oder nicht. Die Cloud baut nur gewollte Server: Sie kopiert `Server-Network/<Server>/template/` nach `runtime/`, lädt die Software, setzt die Secrets ein und startet sie. Nicht gewollte Server legt sie gar nicht an, und weder Proxy noch Lobby verweisen auf sie.
- **Ein Vectora-Plugin** (geplant, Etappe 3): mehrere Gradle-Module (Core, Rechte, Soziales, Moderation, Statistiken, Economy, Lobby, Portale und weitere), gebündelt zu einer Jar pro Plattform: `Vectora-Paper.jar`, `Vectora-Velocity.jar`, `Vectora-Fabric.jar`. Es ersetzt LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal und Chunky.

Fremdsoftware beschränkt sich auf das Nötigste: Paper, Velocity, Fabric und Fabric API, Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind, die Performance-Mods Lithium, FerriteCore, ServerCore und FabricProxy-Lite sowie MariaDB/MySQL.

Mehr dazu: [Architektur im Wiki](https://github.com/VectoDE/Vectora-Network/wiki/Architecture-DE).

## Aufbau des Repositorys

```
Vectora-Network/
  Server-Network/
    Proxy/ Lobby-Server/ Survival-Server/ Creative-Server/ Mod-Server/ End-Server/ Database/
      template/           Configs mit Platzhaltern, in Git
      runtime/            Jars, Welten, Logs; nur lokal, per .gitignore ausgeschlossen
    secrets/              lokale Secrets, per .gitignore ausgeschlossen bis auf vectora.env.example
  Vectora-Lobby/          Plugin-Modul: Lobby
  Vectora-PortalBridge/   Plugin-Modul: Portale und End
  docs/                   Projektdoku; docs/wiki/ wird ins GitHub-Wiki gespiegelt
  .github/                Workflows, Issue-Formulare, Dependabot
```

Geplant: `VectoraCloud/`, weitere Plugin-Module, `Website/`, `Resourcepack/`.

## Schnellstart (Übergang, bis es VectoraCloud gibt)

Bis Etappe 2 laufen die Server unter Windows aus `Server-Network/<Server>/runtime/` über Startskripte. Welche Server laufen sollen, wählst du erst mit VectoraCloud aus; das heutige Startskript startet alle. Du brauchst Java 25 im `PATH` (Paper 26.3 setzt es voraus) und die portable MariaDB 11.8.9 für Windows.

1. Repository klonen.
2. Jeden Ordner `Server-Network/<Server>/template/` nach `Server-Network/<Server>/runtime/` kopieren.
3. `Server-Network/secrets/vectora.env.example` nach `Server-Network/secrets/vectora.env` kopieren und eigene Werte eintragen. Diese Datei nie committen.
4. In den `runtime/`-Ordnern jeden Platzhalter `{{VECTORA_…}}` durch deine Werte ersetzen und die erzeugten Dateien anlegen (`forwarding.secret`, Floodgate-`key.pem`, `eula.txt`). Alle Platzhalter stehen in [`Server-Network/placeholders.md`](Server-Network/placeholders.md). Diesen Schritt übernimmt später VectoraCloud.
5. Server-Software in jeden `runtime/`-Ordner legen, das Repository liefert keine Jars mit. Die Startskripte erwarten `velocity.jar`, `paper.jar` und `fabric-server.jar`; genaue Versionen und Plugin-Dateien stehen unter [Erste Schritte](docs/wiki/Getting-Started-DE.md).
6. `Server-Network\start-network.bat` starten und wählen:
   - `1` alles: Datenbank, danach Proxy und alle fünf Backends
   - `2` nur Datenbank (wie `Server-Network\start-vectora-database.cmd`)
   - `3` nur Proxy
   - `4` nur Backends (Lobby, Survival, Mod, Creative, End)
   - `5` beenden

Standard-Ports des aktuellen Aufbaus:

| Dienst | Port |
|---|---|
| Proxy, Java | 25565 |
| Proxy, Bedrock (Geyser, UDP) | 19132 |
| Lobby | 25566 |
| Survival | 25567 |
| Mod-Server | 25568 |
| Creative | 25569 |
| End | 25570 |
| MariaDB | 3307 |

Von außen sollten nur die Proxy-Ports erreichbar sein.

## Build

```sh
./gradlew build
```

Unter Windows `gradlew.bat build`. Der Gradle-Wrapper lädt Gradle selbst herunter. Jede Abhängigkeit wird gegen eine SHA-256-Prüfsumme geprüft, siehe [docs/dependency-verification.de.md](docs/dependency-verification.de.md).

- Gradle startet ab Java 17 und kompiliert mit einer Java-25-Toolchain. Fehlt ein JDK 25, lädt Gradle eins herunter (Foojay-Resolver).
- Das Bytecode-Ziel ist Java 21 (`--release 21`). Die Server selbst brauchen Java 25, weil Paper 26.3 es verlangt.
- Die CI baut und testet alles mit JDK 25 unter Linux und Windows und führt die Tests zusätzlich auf einer Java-21-Laufzeit aus. Tests, die Paper-Klassen laden, werden dort übersprungen; Details in [docs/java-compatibility.md](docs/java-compatibility.md).
- Code, der Java 25 braucht, trägt `@RequiresJava25` und behält einen Java-21-Weg.

## Doku und Community

- [Wiki](https://github.com/VectoDE/Vectora-Network/wiki) (Deutsch und Englisch, Quellen in `docs/wiki/`)
- [Discussions](https://github.com/VectoDE/Vectora-Network/discussions) für Fragen, Ideen und Ankündigungen
- [Mitmachen](CONTRIBUTING.de.md) · [Verhaltenskodex](CODE_OF_CONDUCT.md) · [Sicherheit](SECURITY.md)

## Lizenz

[GPL-3.0](LICENSE). Minecraft ist eine Marke von Mojang Synergies AB. Vectora steht in keiner Verbindung zu Mojang oder Microsoft.
