# Architektur

[English](Architecture)

> Diese Seite beschreibt größtenteils das geplante Ziel. Was es heute schon gibt, ist mit **heute** markiert.

## Schichten

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

## Proxy (heute)

Velocity ist der einzige Einstieg (Java auf Port 25565, Bedrock auf 19132/UDP). Geyser übersetzt das Bedrock-Protokoll, Floodgate lässt Bedrock-Spieler ohne Java-Account herein. ViaVersion überbrückt neuere Java-Clients, ViaBackwards und ViaRewind decken ältere Clients bis 1.8 ab. Velocity gibt die Spielerdaten im Modus `modern` an die Backends weiter. Siehe [Crossplay](Crossplay-DE).

## Backends (heute)

Paper für Lobby, Survival, Creative und End, Fabric für den Mod-Server. Die Backends laufen auf der neuesten Minecraft-Version. Details und Ports: [Server-Netzwerk](Server-Network-DE).

## Template und Runtime (Etappe 1)

Jeder Server besteht aus zwei Teilen:

- seiner Vorlage: Configs mit Platzhaltern, in Git unter `templates/<Server>/`
- dem Dienst-Ordner: Jars, Welten, Logs und erzeugte Dateien, im Netzwerk-Ordner (dem Cloud-Home) außerhalb des Repositorys. VectolynCloud legt die Vorlage eines gewollten Servers unter `<Home>/local/templates/<Server>/default/` ab und rendert sie nach `<Home>/local/services/<id>` (statische Dienste, bleiben erhalten) oder `<Home>/temp/services/<id>_<run>` (dynamische Dienste, beim Stopp gelöscht). Die Konfiguration liegt in `<Home>/config/`.

Das Cloud-Home ist der Ordner, in dem VectolynCloud läuft. Im Produktivbetrieb liegt es außerhalb jedes Checkouts des Repositorys. Zum Entwickeln baut `./gradlew :cloud:node:runLocal` die `VectolynCloud.jar` und startet sie mit `run/` im Wurzelverzeichnis des Repositorys als Cloud-Home. Git ignoriert `run/` komplett, Secrets und Welten eingeschlossen.

Bis Etappe 2 laufen die vorhandenen Server lokal aus einem `runtime/`-Ordner über die Windows-Startskripte. VectolynCloud nutzt diesen Ordnernamen nicht; der Aufbau steht unter [Cloud home](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/architecture.md#cloud-home) (Englisch).

## VectolynCloud (geplant, Etappe 2)

Ein Java-Node mit Konsole (JLine) und Einrichtungsassistent.

- **Einrichtung und Konfiguration:** wie bei CloudNet. Beim ersten Start läuft ein Einrichtungsassistent in der Konsole, danach konfigurierst du die Cloud über Konfigurationsdateien und Konsolenbefehle (Etappe 2). Später kann das Web-Dashboard all das auch, einschließlich Ersteinrichtung und Server-Auswahl (Etappe 8). Beide nutzen dieselbe Konfigurationsschicht, die Etappe 2 baut.
- **Server auswählen:** Im Assistenten oder in der Cloud-Konfiguration markierst du jeden der sieben Server und jede Minigame-Gruppe als gewollt oder nicht. Die Cloud baut nur gewollte Server: Sie legt die Vorlage des Servers (`templates/<Server>/` im Repository) unter `local/templates/<Server>/default/` ab, rendert sie in den Dienst-Ordner, lädt die Software, setzt die Secrets ein und startet den Server. Nicht gewollte Server legt sie gar nicht an, und weder Proxy noch Lobby verweisen auf sie.
- **Dienste:** Gruppen und Tasks, statische und dynamische Dienste, Autoskalierung nach Spielerzahl, mehrere Nodes im Cluster: ein Head mit eigener CA, einmalige Beitritts-Tokens, gegenseitiges TLS 1.3 zwischen den Nodes; Mitglieder übernehmen Nodes, Gruppen, Tasks und Templates vom Head.
- **Downloads:** Paper und Velocity über die PaperMC-API, Fabric-Meta, Download-API von Geyser und Floodgate, Hangar und Modrinth für die Via-Plugins und Performance-Mods. Die Cloud gleicht Prüfsummen ab und trägt Versionen fest ein; Updates laufen mit Teststart und Rollback.
- **Java-Laufzeiten:** JREs über die Adoptium-API, pro Server-Gruppe passend zur Minecraft-Version (8, 17, 21 oder 25).
- **Datenbank:** Der Assistent bietet drei Wege: MariaDB automatisch herunterladen und verwalten (Windows/Linux), per Docker starten oder eine vorhandene MariaDB/MySQL nutzen. H2 dient als Fallback für Tests. Die Cloud bemisst die Connection-Pools zentral, damit `Too many connections` nicht wiederkommt.
- **Secrets:** entstehen beim ersten Start in `secrets/` bzw. `.env` (gitignored): Velocity-Forwarding-Secret, Floodgate-Key, DB-Passwörter, API-Tokens, Discord-Token.
- **Messaging:** ein eigener Kanal (Netty/WebSocket) zwischen Cloud, Proxy und Servern.
- **API:** REST und WebSocket mit Token-Auth.
- **Backups und Monitoring (Etappe 2):** Backups von Welten und Datenbank, Monitoring von TPS, RAM und Spielern mit Warnungen.
- **Extras (Etappe 8):** Web-Dashboard (inklusive kompletter Einrichtung), Discord-Bot, Website und kosmetischer Store. Der Betreiber hostet sie selbst und bringt eigenen Bot-Token und eigenes Stripe-Konto mit.
- **Betrieb:** Docker-Image auf GHCR (Dockerfile und compose-Datei im Repository), Pterodactyl-Egg, systemd-Unit, Startskripte für Windows, Linux und macOS.

## Vectolyn-Plugin (geplant, Etappe 3)

Mehrere Gradle-Module (Core, Rechte, Soziales, Moderation, Statistiken, Economy, Survival, Creative, Minigames, Anti-Cheat, Lobby, PortalBridge) werden zu einem Plugin pro Plattform gebündelt:

- `Vectolyn-Paper.jar`
- `Vectolyn-Velocity.jar`
- `Vectolyn-Fabric.jar`

Welche Module eine Server-Gruppe aktiviert, steht in der Cloud-Konfiguration; die Cloud verteilt die passende Jar. Versionsadapter decken Minecraft 1.8.8 bis 26.3 ab. Das Plugin ersetzt LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal und Chunky.

Heute enthält das Repository zwei frühe Module, `plugins/lobby/paper` und `plugins/portal/paper`, die der Gradle-Build kompiliert.

## Build

Gradle mit Kotlin DSL, Wrapper und Toolchains. Die Toolchain ist Java 25, das Bytecode-Ziel Java 21. Die CI baut und testet mit JDK 25 und führt die Tests zusätzlich auf einer Java-21-Laufzeit aus. Code, der Java 25 braucht, trägt `@RequiresJava25`, prüft die Laufzeit und behält einen Java-21-Weg.

## Erlaubte Fremdsoftware

Paper, Velocity, Fabric und Fabric API; Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind; Lithium, FerriteCore, ServerCore und FabricProxy-Lite auf dem Mod-Server; MariaDB/MySQL. Für 1.8.8-Server-Gruppen wählen wir in Etappe 2 einen modernen Fork, der unter Java 21 läuft.
