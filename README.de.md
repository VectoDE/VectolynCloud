<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/png/readme-header-dark@2x.png">
    <img alt="VectolynCloud" src="branding/png/readme-header-light@2x.png" width="1280">
  </picture>
</h1>

<p align="center"><strong>Run your own Minecraft network.</strong></p>

[![CI](https://github.com/VectoDE/VectolynCloud/actions/workflows/ci.yml/badge.svg)](https://github.com/VectoDE/VectolynCloud/actions/workflows/ci.yml)
[![CodeQL](https://github.com/VectoDE/VectolynCloud/actions/workflows/codeql.yml/badge.svg)](https://github.com/VectoDE/VectolynCloud/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/VectoDE/VectolynCloud/badge)](https://scorecard.dev/viewer/?uri=github.com/VectoDE/VectolynCloud)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16026/badge)](https://www.bestpractices.dev/projects/16026)
[![Lizenz: GPL-3.0](https://img.shields.io/github/license/VectoDE/VectolynCloud)](LICENSE)

[English](README.md) · **Deutsch**

Vectolyn ist ein Open-Source-Baukasten (GPL-3.0), mit dem du dein eigenes Minecraft-Netzwerk betreibst. Darauf spielen Java-Spieler (1.8 bis 26.3) und Bedrock-Spieler zusammen.

Wir selbst betreiben kein Netzwerk: Es gibt keinen offiziellen Server, keine Domain und keine gehostete Website, keinen Discord-Bot und keinen Store. Website, Discord-Bot und Store liegen als Code in diesem Repository. Du konfigurierst sie mit deinem eigenen Bot-Token und deinem eigenen Stripe-Konto und hostest sie selbst.

Das langfristige Ziel: Du startest `java -jar VectolynCloud.jar` unter Windows, Linux, macOS, in Docker oder auf Pterodactyl, beantwortest einen Einrichtungsassistenten, und danach läuft das komplette Netzwerk mit Datenbank, Proxy, Lobby, Spielmodi und Minigames. Ein Release wird `VectolynCloud.jar`, Startskripte für Windows, Linux und macOS und ein Docker-Image auf GHCR enthalten, alles signiert und mit Provenance. Plugin-Jars hängen nicht am Release. Dockerfile, compose-Datei und Pterodactyl-Egg liegen in diesem Repository.

## Stand

**Etappe 2 von 8: VectolynCloud-Kern.** Etappe 1, das Fundament, ist fertig und als signiertes Pre-Release [v0.1.1](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.1) erschienen. In Etappe 2 entsteht die Cloud Modul für Modul; die Aufgabenliste steht in [#11](https://github.com/VectoDE/VectolynCloud/issues/11), der Gesamtplan auf der Roadmap. Ein nutzbares Cloud-Release gibt es noch nicht; v0.1.1 ist ein frühes Pre-Release. Vieles unter *Architektur* ist noch Plan, kein fertiger Code.

- Roadmap und Fortschritt: [Vectolyn Roadmap](https://github.com/users/VectoDE/projects/11)
- Aktuelle Etappe: [#11 Stage 2: VectolynCloud core](https://github.com/VectoDE/VectolynCloud/issues/11)

| Etappe | Inhalt |
|---|---|
| 1 | Fundament: Repository, Build, Konfigurationsfehler |
| 2 | VectolynCloud-Kern |
| 3 | Vectolyn-Plugin-Basis: Core, Rechte, Messaging, Portale/End, Sync, Versionsadapter |
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

 VectolynCloud (geplant) startet, skaliert und überwacht alles oben.
```

- **Proxy:** Velocity ist der einzige Einstieg. Geyser und Floodgate lassen Bedrock-Clients herein, die Via-Plugins übersetzen ältere Java-Versionen. Die Backends laufen auf der neuesten Minecraft-Version.
- **Backends:** Paper für Lobby, Survival, Creative, End und später die Minigames. Fabric für den Mod-Server.
- **Keine Command-Blöcke:** Sie sind auf jedem Server aus und lassen sich nicht wieder einschalten; die Bridges für Paper und 1.8.8 entfernen sie außerdem aus den Welten. Team-Befehle wirken nur für die Konsole und Spieler mit der Berechtigung; das [Rechte-Audit](docs/cloud/rights-audit.md) listet jeden auf.
- **VectolynCloud** (geplant, Etappe 2): ein Java-Node mit Konsole und Einrichtungsassistent. Er lädt Server-Software herunter und prüft sie, verwaltet JREs und Datenbank, erzeugt Secrets, skaliert Minigame-Server und bietet eine REST-/WebSocket-API. Die Einrichtung läuft wie bei CloudNet: Assistent in der Konsole beim ersten Start, danach Konfigurationsdateien und Konsolenbefehle (Etappe 2). Später soll das Web-Dashboard das alles auch können, einschließlich der Ersteinrichtung (Etappe 8). Im Assistenten, in der Konfiguration oder später im Dashboard markierst du jeden der sieben Server und jede Minigame-Gruppe als gewollt oder nicht. Die Cloud baut nur gewollte Server: Sie kopiert die Vorlage des Servers (`templates/<Server>/` in diesem Repository) nach `runtime/`, lädt die Software, setzt die Secrets ein und startet sie. Nicht gewollte Server legt sie gar nicht an, und weder Proxy noch Lobby verweisen auf sie.
- **Ein Vectolyn-Plugin** (geplant, Etappe 3): mehrere Gradle-Module (Core, Rechte, Soziales, Moderation, Statistiken, Economy, Lobby, Portale und weitere), gebündelt zu einer Jar pro Plattform: `Vectolyn-Paper.jar`, `Vectolyn-Velocity.jar`, `Vectolyn-Fabric.jar`. Es ersetzt LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal und Chunky.

Fremdsoftware beschränkt sich auf das Nötigste: Paper, Velocity, Fabric und Fabric API, Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind, die Performance-Mods Lithium, FerriteCore, ServerCore und FabricProxy-Lite sowie MariaDB/MySQL.

Mehr dazu: [Architektur im Wiki](https://github.com/VectoDE/VectolynCloud/wiki/Architecture-DE).

## Aufbau des Repositorys

```
VectolynCloud/
  cloud/                  VectolynCloud, je Teil ein Gradle-Modul: launcher, node, config, api,
                          console, setup, database, download, template, network, backup, monitor,
                          cluster
    dist/                 Startskripte, Docker, Pterodactyl-Egg, systemd-Unit, vectolyn.env.example
  common/
    protocol/             Leitungsformat des Cloud-Kanals
    client/               Client, über den die Plugins mit der Cloud sprechen
  plugins/<Feature>/<Plattform>/
                          je Feature und Plattform ein Modul und eine Jar
    bridge/               Cloud-Bridge: common, paper, legacy, velocity
    lobby/paper/          Lobby
    portal/paper/         Portale und End
  templates/<Server>/     Server-Configs mit Platzhaltern, in Git
  services/               Dashboard, Website, Discord-Bot, Store (geplant)
  resourcepack/           Vectolyn-Resourcepack (geplant)
  docs/                   Projektdoku; docs/wiki/ wird ins GitHub-Wiki gespiegelt
  build-logic/, gradle/   Gradle-Konventionen, Versionskatalog, Abhängigkeitsprüfung
  .github/                Workflows, Issue-Formulare, Dependabot
```

Server-Laufzeiten (Jars, Welten, Logs) und Secrets gehören nicht ins Repository. Sie liegen im Cloud-Home, dem Ordner, in dem VectolynCloud läuft: `config/` enthält die Konfiguration, `local/` die Vorlagen und die Welten der statischen Dienste, `storage/` die Downloads, `secrets/` die Secrets (Aufbau: [architecture](docs/cloud/architecture.md#cloud-home), Englisch). Bei der lokalen Entwicklung ist das `run/` im Wurzelverzeichnis des Repositorys (siehe [Lokal starten](#lokal-starten)); `.gitignore` hält den ganzen Ordner aus Git heraus.

## Schnellstart (Übergang, bis es VectolynCloud gibt)

Bis Etappe 2 laufen die Server unter Windows aus einem Netzwerk-Ordner außerhalb des Repositorys (zum Beispiel `C:\Vectolyn`, das spätere Cloud-Home) über Startskripte. Welche Server laufen sollen, wählst du erst mit VectolynCloud aus; das heutige Startskript startet alle. Du brauchst Java 25 im `PATH` (Paper 26.3 setzt es voraus) und die portable MariaDB 11.8.9 für Windows.

1. Repository klonen.
2. Jeden Ordner `templates/<Server>/` nach `<Netzwerk-Ordner>\<Server>\runtime\` kopieren.
3. `cloud/dist/secrets/vectolyn.env.example` nach `<Netzwerk-Ordner>\secrets\vectolyn.env` kopieren und eigene Werte eintragen. Diese Datei nie committen.
4. In den `runtime/`-Ordnern jeden Platzhalter `{{VECTOLYN_…}}` durch deine Werte ersetzen und die erzeugten Dateien anlegen (`forwarding.secret`, Floodgate-`key.pem`, `eula.txt`). Alle Platzhalter stehen in [`templates/placeholders.md`](templates/placeholders.md). Diesen Schritt übernimmt später VectolynCloud.
5. Server-Software in jeden `runtime/`-Ordner legen, das Repository liefert keine Jars mit. Die Startskripte erwarten `velocity.jar`, `paper.jar` und `fabric-server.jar`; genaue Versionen und Plugin-Dateien stehen unter [Erste Schritte](docs/wiki/Getting-Started-DE.md).
6. `start-network.bat` und `start-vectolyn-database.cmd` aus `cloud/dist/manual/` in den Netzwerk-Ordner kopieren, dort `start-network.bat` starten und wählen:
   - `1` alles: Datenbank, danach Proxy und alle fünf Backends
   - `2` nur Datenbank (wie `start-vectolyn-database.cmd`)
   - `3` nur Proxy
   - `4` nur Backends (Lobby, Survival, Mod, Creative, End)
   - `5` beenden

Standard-Ports des aktuellen Aufbaus:

| Dienst | Port |
|---|---|
| Proxy, Java | 25565 |
| Proxy, Bedrock (Geyser: RakNet UDP, NetherNet-Signaling TCP) | 19132 |
| Proxy, Bedrock NetherNet (Geyser, UDP, immer Bedrock + 1) | 19133 |
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

## Lokal starten

```sh
./gradlew :cloud:node:runLocal
```

Das baut `VectolynCloud.jar` und startet es mit `run/` im Wurzelverzeichnis des Repositorys als Cloud-Home. Beim ersten Start wird der Ordner angelegt. Der Einrichtungsassistent stellt seine Fragen im selben Terminal, danach lädt die Cloud die Server-Software herunter und startet das Netzwerk. Konfiguration, Secrets, Welten, Logs und Downloads bleiben alle in `run/`, und Git ignoriert den Ordner vollständig. Wer neu anfangen will, löscht ihn einfach.

Unter Gradle hat die Cloud kein echtes Terminal. Die Konsole liest deshalb einfache Zeilen ohne Bearbeitung und Vervollständigung, und Secrets, die du im Einrichtungsassistenten eintippst, bleiben sichtbar. Mit `--console=plain` kommt dir die Fortschrittszeile von Gradle nicht in die Quere. Beende das Netzwerk mit `shutdown` in der Konsole: Strg+C bricht Gradle ab, und unter Windows endet die Cloud dann ohne sauberes Herunterfahren. Details in [docs/cloud/launcher.md](docs/cloud/launcher.md#local-start-from-the-repository).

Als root oder Administrator verweigert der Launcher den Start. Läuft deine Entwicklungs-Shell trotzdem mit erhöhten Rechten, hängst du `-PallowRoot=true` an (gibt `--allow-root` weiter).

`run/` ist nur zum Entwickeln und Testen da. Produktivserver bekommen ein eigenes Cloud-Home außerhalb jedes Checkouts, siehe [docs/cloud/operations.de.md](docs/cloud/operations.de.md).

## Doku und Community

- [Wiki](https://github.com/VectoDE/VectolynCloud/wiki) (Deutsch und Englisch, Quellen in `docs/wiki/`)
- [Discussions](https://github.com/VectoDE/VectolynCloud/discussions) für Fragen, Ideen und Ankündigungen
- [Mitmachen](CONTRIBUTING.de.md) · [Verhaltenskodex](CODE_OF_CONDUCT.md) · [Sicherheit](SECURITY.md)

## Lizenz

[GPL-3.0](LICENSE). Minecraft ist eine Marke von Mojang Synergies AB. Vectolyn steht in keiner Verbindung zu Mojang oder Microsoft.
