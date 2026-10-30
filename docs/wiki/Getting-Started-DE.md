# Erste Schritte

[English](Getting-Started)

Es gibt zwei Wege: den heutigen Übergangsaufbau und die Einrichtung über VectolynCloud, geplant für Etappe 2.

## Heute: Übergangsaufbau (Windows)

Bis es VectolynCloud gibt, laufen die Server lokal aus einem Netzwerk-Ordner außerhalb des Repositorys (zum Beispiel `C:\Vectolyn`, das spätere Cloud-Home) über Windows-Startskripte. Das Startskript startet immer alle Server. Die Auswahl kommt mit VectolynCloud.

Du brauchst:

- Java 25 im `PATH`. Paper 26.3 und der Fabric-Server für 26.3 setzen es voraus, Velocity läuft ebenfalls damit.
- Die portable MariaDB 11.8.9 für Windows x64 (ZIP-Archiv).

### 1. Runtime-Ordner anlegen

1. Repository klonen.
2. Jeden Ordner `templates/<Server>/` nach `<Netzwerk-Ordner>\<Server>\runtime\` kopieren.
3. `cloud/dist/secrets/vectolyn.env.example` nach `<Netzwerk-Ordner>\secrets\vectolyn.env` kopieren und eigene Werte eintragen. Diese Datei nie committen.
4. In den `runtime/`-Ordnern jeden Platzhalter `{{VECTOLYN_…}}` durch deine Werte ersetzen. [`placeholders.md`](https://github.com/VectoDE/VectolynCloud/blob/main/templates/placeholders.md) listet jeden Platzhalter mit Fundstellen und lokalem Standardwert auf (Ports, Heap-Größen, Serverliste des Proxys).
5. Die Dateien anlegen, die nicht aus Templates kommen: `Proxy/runtime/forwarding.secret` (das Forwarding-Secret), die Floodgate-`key.pem` (der Base64-Wert, dekodiert zu 16 Bytes, dieselbe Datei auf dem Proxy und auf jedem Backend) und `eula.txt`, sobald du die Minecraft-EULA akzeptiert hast.

Die Schritte 2 bis 5 übernimmt ab Etappe 2 VectolynCloud. Diese Ordner (`<Server>\runtime`, `Database\runtime`) gehören nur zu den Startskripten von Hand; VectolynCloud nutzt einen eigenen Aufbau (`config/`, `local/`, `database/`, `storage/`), siehe [Cloud home](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/architecture.md#cloud-home) (Englisch).

### 2. Server-Software einspielen

Das Repository liefert keine Jars mit. Der aktuelle Aufbau läuft mit diesen Versionen. Die Startskripte erwarten die Dateinamen aus der zweiten Spalte.

| Ordner (`runtime/`) | Server-Jar | Plugins oder Mods |
|---|---|---|
| `Proxy` | `velocity.jar` (Velocity 4.3.0) | `plugins/`: Geyser-Velocity, Floodgate (Velocity), LuckPerms-Velocity 5.5.71, ViaVersion 5.12.2, ViaBackwards 5.12.2, ViaRewind 4.2.0 |
| `Lobby-Server`, `Survival-Server`, `Creative-Server`, `End-Server` | `paper.jar` (Paper 26.3) | `plugins/`: Floodgate (Spigot), LuckPerms-Bukkit 5.5.71, TAB 6.2.1, PlaceholderAPI 2.12.3, WorldEdit 7.4.6, WorldGuard 7.0.20 |
| `Mod-Server` | `fabric-server.jar` (Fabric Loader 0.20.1, MC 26.3) | `mods/`: Fabric API, FabricProxy-Lite 2.12.0, Floodgate-Fabric 2.2.7-b69, LuckPerms-Fabric 5.5.85, Lithium 0.26.2+mc26.3, FerriteCore 9.0.0, ServerCore 1.5.20+26.3, Chunky 1.5.3, spark 1.10.187, ProxyPortal 2.0.0+26.1 |
| `Database` | MariaDB 11.8.9, entpackt nach `mariadb-11.8.9-winx64/` | keine |

Die Vectolyn-Plugins entstehen in diesem Repository (siehe *Plugins bauen* unten). `VectolynLobby` gehört auf die Lobby, `VectolynPortalBridge` auf Lobby, Survival und Creative.

Vor dem ersten Datenbankstart den Datenordner `Database/runtime/data` mit `mariadb-install-db.exe` aus dem `bin`-Ordner von MariaDB anlegen. Danach das ausgefüllte `init.sql` einmal als MariaDB-root ausführen. Es legt die LuckPerms-Datenbank und den Benutzer an.

### 3. Starten

`start-network.bat` und `start-vectolyn-database.cmd` aus `cloud/dist/manual/` in den Netzwerk-Ordner kopieren, dort `start-network.bat` ausführen und eine Option wählen:

| Option | Startet |
|---|---|
| `1` | Alles: die Datenbank, nach fünf Sekunden den Proxy und alle fünf Backends |
| `2` | Nur die Datenbank |
| `3` | Nur den Proxy |
| `4` | Nur die Backends: Lobby, Survival, Mod, Creative, End |
| `5` | Nichts, schließt das Fenster |

Jeder Server öffnet ein eigenes Fenster und startet fünf Sekunden nach dem Stoppen neu. Zum endgültigen Beenden das Fenster schließen. `start-vectolyn-database.cmd` startet nur die Datenbank, wie Option `2`.

Danach mit einem Java-Client zu `localhost:25565` verbinden oder mit einem Bedrock-Client zu `localhost`, Port `19132`.

Ports aller Server: [Server-Netzwerk](Server-Network-DE).

## Geplant: VectolynCloud (ab Etappe 2)

VectolynCloud wird wie CloudNet eingerichtet und konfiguriert:

1. `java -jar VectolynCloud.jar` unter Windows, Linux, macOS, in Docker oder auf Pterodactyl starten.
2. Beim ersten Start läuft ein Einrichtungsassistent in der Konsole. Er fragt unter anderem, wie die Datenbank laufen soll (verwaltete MariaDB, Docker oder extern) und welche der sieben Server und Minigame-Gruppen du willst.
3. Die Cloud erzeugt die Secrets, lädt Server-Software und Java-Laufzeiten herunter und prüft sie, baut jeden gewollten Server aus seiner Vorlage in einen eigenen Ordner unter `local/services/` und startet ihn. Der Start zeigt ein Banner und eine Zeile je Schritt (Konfiguration, Datenbank, Software, Vorlagen, Dienste). Deine Konfiguration liegt in `config/`. Nicht gewählte Server entstehen gar nicht, und weder Proxy noch Lobby verweisen auf sie.
4. Danach änderst du den Aufbau über Konfigurationsdateien und Konsolenbefehle.

Ab Etappe 8 soll das Web-Dashboard dasselbe können, auch die Ersteinrichtung und die Server-Auswahl. Assistent, Dateien, Konsolenbefehle und Dashboard arbeiten auf einer gemeinsamen Konfigurationsschicht.

## Aus dem Repository: VectolynCloud lokal starten

So probierst du den aktuellen Stand von VectolynCloud oder eine eigene Änderung aus:

```sh
./gradlew :cloud:node:runLocal --console=plain
```

Das baut `VectolynCloud.jar` und startet es mit `run/` im Wurzelverzeichnis des Repositorys als Cloud-Home. Der Einrichtungsassistent fragt im selben Terminal. Konfiguration, Secrets, Welten, Logs und Downloads bleiben alle in `run/`, und Git ignoriert den Ordner komplett. Beende das Netzwerk mit `shutdown` in der Konsole; wer neu anfangen will, löscht `run/`. Läuft deine Shell als Administrator oder root, hängst du `-PallowRoot=true` an.

`run/` ist nur zum Entwickeln da. Ein echtes Netzwerk startest du aus einem Release in einem eigenen Cloud-Home außerhalb des Checkouts, siehe [Betrieb](Operations-DE). Details zum Task: [docs/cloud/launcher.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/launcher.md#local-start-from-the-repository) (Englisch).

## Plugins bauen

```sh
./gradlew build        # Linux, macOS, Git Bash
gradlew.bat build      # Windows cmd / PowerShell
```

Gradle startet ab Java 17 und kompiliert mit einer Java-25-Toolchain, die es bei Bedarf herunterlädt. Das Bytecode-Ziel ist Java 21. Die Jars liegen danach in `plugins/lobby/paper/build/libs/` und `plugins/portal/paper/build/libs/`. Details: [Java-Kompatibilität](https://github.com/VectoDE/VectolynCloud/blob/main/docs/java-compatibility.md).

## Plugin-Referenz

Beide Plugins haben keine Befehle und keine Einstellungen, die du anfassen musst. So verhalten sie sich heute. Ab Etappe 3 und 4 ersetzt sie das Vectolyn-Plugin.

### VectolynLobby

Gehört auf den Lobby-Server.

- Beim ersten Start baut es die Himmelsinsel-Lobby um `0, 100, 0` in der ersten Welt des Servers und schreibt danach `built: true` in `plugins/VectolynLobby/config.yml`. Steht der Wert wieder auf `false`, baut es die Insel beim nächsten Start neu; vorhandene Blöcke in dem Bereich werden überschrieben.
- Bei jedem Start schaltet es in dieser Welt Tageszeit- und Wetterwechsel ab und setzt den Spawn auf die Plattform.
- Spieler werden beim Betreten zum Spawn teleportiert. Sie nehmen keinen Schaden und bekommen keinen Hunger.
- Blöcke abbauen und setzen geht nur mit der Berechtigung `vectolyn.lobby.build`. Die `plugin.yml` gibt sie nur Operatoren (`default: op`); andere Spieler bekommen sie nur über ein Rechte-Plugin.

### VectolynPortalBridge

Gehört auf Lobby, Survival und Creative.

- Betritt ein Spieler ein End-Portal, bricht das Plugin den lokalen Teleport ab und schickt über den Plugin-Kanal `BungeeCord` eine `Connect`-Nachricht, die den Spieler auf den Backend-Server `end` verschiebt.
- Dafür braucht die `velocity.toml` `bungee-plugin-message-channel = true` (im Proxy-Template gesetzt) und einen Server namens `end` in der Liste `[servers]`.
- Lässt sich die Nachricht nicht senden, sieht der Spieler „The End server is currently unavailable.“ und im Server-Log steht eine Warnung.
