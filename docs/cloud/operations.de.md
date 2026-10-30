# VectoraCloud betreiben

English: [operations.md](operations.md)

Diese Seite beschreibt, wie du `VectoraCloud.jar` unter Windows, Linux (VPS oder eigener Server, mit systemd), macOS, Docker und Pterodactyl betreibst und wie du prüfst, dass ein Download wirklich aus diesem Repository stammt. Wir hosten nichts: Jedes Netzwerk, das mit Vectora gebaut wird, läuft auf dem Rechner seines Betreibers.

Die Dateien liegen in [`cloud/dist/`](../../cloud/dist):

| Datei | Zweck |
|---|---|
| `start.sh` | Startskript für Linux und macOS (bash 3.2 oder neuer) |
| `start.bat`, `start.ps1` | Startskripte für Windows (Eingabeaufforderung, Windows PowerShell 5.1 oder PowerShell 7) |
| `systemd/vectora-cloud.service` | systemd-Unit mit eigenem Benutzer und Härtung |
| `docker/Dockerfile`, `docker/compose.yml`, `docker/setup.example.yml` | Container-Image und eine Compose-Datei mit MariaDB |
| `pterodactyl/egg-vectora-cloud.json` | Pterodactyl-Egg |
| `package.sh` | baut die Release-Archive (nutzt der Release-Workflow) |

## Was ein Release enthält

Der Release-Workflow ([`release.yml`](../../.github/workflows/release.yml)) läuft bei einem `v*`-Tag und hängt an:

| Datei | Inhalt |
|---|---|
| `VectoraCloud.jar` | die Cloud, für `java -jar`, Docker und Pterodactyl |
| `VectoraCloud-<version>-windows.zip` | `VectoraCloud.jar`, `start.bat`, `start.ps1` |
| `VectoraCloud-<version>-linux-macos.tar.gz` | `VectoraCloud.jar`, `start.sh` (ausführbar) |
| `SHA256SUMS` | SHA-256 der drei Dateien darüber |
| `<datei>.sigstore.json` | cosign-Signatur-Bundle für jede Datei und für `SHA256SUMS` |
| `vectora.intoto.jsonl` | SLSA-Build-Provenance für jede Datei aus `SHA256SUMS`, als Sigstore-Bundle der GitHub-Attestierung |

Bevor ein Tag gepusht wird, und am Ende jeder Etappe, läuft die Aufräum-Prüfung aus [docs/openssf.de.md](../openssf.de.md#die-aufräum-prüfung): keine offenen Pull Requests, nichts Behebbares mehr unter *Security and quality*, und CI, CodeQL, Dependency Review und Scorecard laufen auf `main` erneut, bis das gilt.

Die Startskripte kommen als ein Archiv pro Betriebssystem, nicht als lose Dateien. Ein heruntergeladenes `start.sh` verliert sein Ausführungsrecht, im tar bleibt es erhalten. Das zip behält die CRLF-Zeilenenden, die die Windows-Skripte brauchen. Und jedes Archiv ist ein einziger Download mit dem passenden Jar. Das Jar hängt zusätzlich einzeln am Release. Die Archive sind reproduzierbar (feste Reihenfolge, feste Besitzer und Zeitstempel), derselbe Tag ergibt also beim zweiten Bauen dieselbe SHA-256.

Das Container-Image landet unter `ghcr.io/vectode/vectora-cloud` mit der Version als Tag (`0.2.0`, ohne `v`). `latest` bekommen nur stabile Releases. `v0.*` und Tags mit Zusatz (`v1.0.0-rc.1`) sind Vorabversionen und verschieben `latest` nicht. Im Image steckt genau das `VectoraCloud.jar` des Releases: Der Workflow übergibt das signierte Jar an den Image-Build, statt es neu zu kompilieren, und prüft vorher seine SHA-256 gegen `SHA256SUMS`.

## Voraussetzungen

- **Java 21 oder neuer** für die Cloud selbst. Die Server, die sie startet, bekommen die Java-Version, die ihre Minecraft-Version braucht. Passt kein installiertes Java, lädt die Cloud ein Temurin-JRE nach `<home>/storage/jre/` (siehe [node.md](node.md#java-runtimes)).
- Ein normales Benutzerkonto. Als root oder Administrator startet die Cloud nicht (siehe [unten](#nie-als-root-oder-administrator)).
- Arbeitsspeicher: der Heap der Cloud (`VECTORA_XMX`, Standard 512M) plus der Heap jedes Servers, den sie betreibt.
- **Internetzugang beim ersten Start einer Gruppe.** Dann lädt die Cloud die Server-Software (Paper, Velocity, Fabric, PandaSpigot) und die Plugins und Mods aus dem Katalog: Geyser und Floodgate für Bedrock, ViaVersion, ViaBackwards und ViaRewind, damit Java-Clients von 1.8 bis 26.3 beitreten können, dazu LuckPerms und die Performance-Mods für Fabric. Jede Datei wird gegen ihre Prüfsumme geprüft und in `config/software.lock.yml` festgehalten. Spätere Starts nehmen sie ohne Netz aus `storage/artifacts/` ([download.md](download.md#what-the-node-installs)).

## Erster Start: was in jedem Server landet

Die Vorlagen stecken im Jar: Für jeden Server, der gewünscht ist, schreibt der erste Start `local/templates/<Server>/default/`. Einen Ordner, den du schon hast, rührt die Cloud nicht an ([node.md](node.md#built-in-templates)). Ein abgeschalteter Server hinterlässt keinen Vorlagenordner.

Vor jedem Start füllt der Node den Ordner des Servers: das Server-Jar, die Plugins (bei Fabric die Mods), die seine Gruppe braucht, und die VectoraCloud-Bridge seiner Plattform. Was er dort ablegt, notiert er in `plugins/.vectora-managed` (bei Fabric `mods/`). Will die Gruppe eine dieser Dateien nicht mehr, entfernt er sie wieder. Plugins, die du selbst hineinkopierst, bleiben liegen. Ein PandaSpigot-Server (1.8.8) startet nicht ohne seine Bridge, denn nur sie prüft den Token des Legacy-Proxys. Einzelheiten: [node.md](node.md#server-software).

## Startskripte

```sh
./start.sh [Argumente]                 # Linux, macOS
start.bat [Argumente]                  # Windows, Eingabeaufforderung
powershell -ExecutionPolicy Bypass -File .\start.ps1 [Argumente]   # Windows PowerShell
```

Alle Argumente gehen unverändert an `VectoraCloud.jar`: `--non-interactive`, `--setup-file <datei>`, `--lang de|en`, `--allow-root`, `--home <pfad>`, `--help`, `--version` ([launcher.md](launcher.md#arguments)).

| Variable | Standard | Bedeutung |
|---|---|---|
| `VECTORA_HOME` | Ordner des Skripts | Cloud-Home. Das Skript wechselt hinein, relative Pfade in Argumenten (`--setup-file setup.yml`) gelten also relativ zum Home. |
| `VECTORA_JAR` | `VectoraCloud.jar` neben dem Skript | Pfad zum Jar |
| `VECTORA_XMX` | `512M` | maximaler Heap des Cloud-Prozesses, Ziffern plus `K`, `M` oder `G` |
| `JAVA_HOME` | (nicht gesetzt) | zu verwendendes Java. Fehlt darin `bin/java`, sagt das Skript das und nimmt `java` aus dem `PATH`. |
| `JDK_JAVA_OPTIONS` | (nicht gesetzt) | weitere JVM-Optionen, liest der `java`-Launcher selbst |

So wird Java gefunden: zuerst `JAVA_HOME`, dann der `PATH`. Gibt es gar kein Java, nennt das Skript die Bezugsquelle (<https://adoptium.net/de/temurin/releases/>) und endet mit 3. Die Version prüft `VectoraCloud.jar` selbst. Seine erste Klasse ist für Java 8 kompiliert, deshalb melden Java 8 bis 20 „VectoraCloud braucht Java 21 oder neuer“ samt gefundener Version und Pfad statt eines Klassendatei-Fehlers. Aus demselben Grund geben die Skripte keine JVM-Option mit, die alte Java-Versionen ablehnen. Den nativen Zugriff für die Konsole erlaubt das Manifest des Jars (`Enable-Native-Access: ALL-UNNAMED`).

Sicherheit der Skripte:

- `start.sh` läuft mit `set -euo pipefail`, setzt jede Expansion in Anführungszeichen und startet Java mit `exec`, damit Java die Signale direkt bekommt (systemd, `docker stop`). Es besteht ShellCheck und läuft mit der bash 3.2 von macOS.
- `start.bat` liest die Umgebung mit verzögerter Expansion und entfernt Anführungszeichen. `&`, `|`, `<`, `>` oder Klammern in einer Variable werden deshalb nie als Befehl ausgeführt. Die Argumente gehen mit ausgeschalteter verzögerter Expansion weiter, ein `!` darin bleibt also erhalten.
- `start.ps1` kommt ohne `Invoke-Expression` und ohne Befehls-String aus, die Argumente gehen als Array an Java. Es besteht PSScriptAnalyzer.
- Keines der Skripte nutzt `sudo` oder fordert Admin-Rechte an.

Der CI-Workflow [`operations.yml`](../../.github/workflows/operations.yml) führt jedes Skript unter Linux, macOS und Windows aus: Version und Hilfe, ein zu altes Java 17, gar kein Java, ein `JAVA_HOME` ohne Java, ein fehlendes Jar, ein kaputtes `VECTORA_XMX`, Werte mit `&` und Anführungszeichen in der Umgebung, ein Ordnername mit Leerzeichen, Klammern, `&` und `!`, ein Start als root mit und ohne `--allow-root` und die Skripte aus den Release-Archiven.

### Exit-Codes

| Code | Bedeutung |
|---|---|
| 0 | normal beendet, oder `--help` / `--version` |
| 1 | der Node hat beim Start eine Ausnahme geworfen |
| 2 | als root oder Administrator gestartet, ohne `--allow-root` |
| 3 | kein Java gefunden, oder Java älter als 21 |
| 4 | eine eingebettete Bibliothek fehlt oder hat die falsche SHA-256 |
| 5 | kein Node-Einstiegspunkt in diesem Build |
| 64 | ungültiges Argument, oder `VECTORA_XMX` ist keine Größe |
| 66 | `VectoraCloud.jar` oder der Home-Ordner fehlt (Startskripte) |
| 143 | durch SIGTERM beendet (systemd, `docker stop`), nach dem sauberen Herunterfahren |

### Nie als root oder Administrator

Die Cloud lädt Server-Software herunter und führt sie aus. Mit root- oder Administratorrechten würde ein Fehler oder ein manipulierter Download das ganze System treffen. Der Launcher verweigert so einen Start deshalb mit Exit-Code 2 und erklärt, wie es richtig geht ([launcher.md](launcher.md#root-and-administrator-check)). `--allow-root` oder `VECTORA_ALLOW_ROOT=true` erlauben ihn trotzdem und warnen bei jedem Start. Nutze das nur, wenn es wirklich keinen anderen Weg gibt.

## Erster Start: das Setup

Solange in `cloud.yml` nicht `setup-complete: true` steht, startet die Cloud keinen Server. Mit Terminal stellt sie ihre Fragen. Ohne Terminal (systemd, Docker ohne `-it`, Pterodactyl) braucht sie eine Setup-Datei:

```sh
./start.sh --non-interactive --setup-file setup.yml
```

Format und Regeln: [setup.md](setup.md). Die Datei enthält kein Secret. Ein Datenbank-Passwort kommt aus einer Datei (`database.password-file`) oder aus `secrets/vectora.env`. Ist das Setup abgeschlossen, wird die Datei ignoriert.

## Was wo liegt und Update von einem alten Home

Alles, was die Cloud ablegt, liegt unter dem Home ([Aufbau](architecture.md#cloud-home), Englisch). Für den Betrieb zählt:

| Ordner | Sichern? | Hinweis |
|---|---|---|
| `config/` | ja | die Konfiguration, klein |
| `local/` | ja | von dir geänderte Vorlagen und die Welten der statischen Dienste |
| `database/` | mit der Datenbank | verwaltete MariaDB; nimm den Dump aus dem [Backup](backup.md), kopiere keine laufenden Datendateien |
| `secrets/` | ja, getrennt | nur für den Besitzer lesbar, nie in ein Repository |
| `storage/`, `temp/`, `logs/` | nein | Downloads, Java-Runtimes und entpackte Bibliotheken entstehen von selbst neu |
| `backups/` | das ist das Backup | |
| `state/` | nein | Lock, Dienst-Status, Konsolen-Verlauf |

**Update.** Ein Home aus der Zeit vor diesem Aufbau (`cloud.yml`, `groups/`, `<Server>/template`, `<Server>/runtime`, `cache/` auf oberster Ebene) wird beim ersten Start der neuen Version umgezogen. Vorher legt die Cloud in `backups/home-layout-migration-<Zeit>/` eine Kopie von Konfiguration, Vorlagen und `state/services.json` an; Welten, Datenbank und Downloads werden nur umbenannt und verschoben, auch große Welten brauchen also Sekunden. Beende die alte Cloud samt Servern, bevor du die neue startest: Die Migration läuft nicht, solange ein Server des vorigen Laufs noch lebt. Was sie Schritt für Schritt tut und was passiert, wenn ein Ordner nicht zuzuordnen ist, steht in [node.md](node.md#home-layout-migration) (Englisch). Lösche den Backup-Ordner erst, wenn das Netzwerk einmal sauber lief.

**Windows-Konsole.** `start.bat` und `start.ps1` stellen die Konsole auf UTF-8 um (`chcp 65001`) und starten Java mit `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`, damit Umlaute im Log stimmen. Startest du `java -jar VectoraCloud.jar` von Hand in einer alten Konsole, setze dasselbe.

**Logs.** `logs/cloud.log` ist das Log der Cloud selbst, in UTF-8 und mit vollen Stacktraces (Secrets geschwärzt). Jeder Start beginnt eine neue Datei, die letzten fünf bleiben als `cloud.1.log` bis `cloud.5.log`. Das Log jedes Servers ist `logs/services/<id>.log`. Was ein Start auf dem Bildschirm zeigt, steht in [node.md](node.md#start-output).

## Windows

1. Java 21 oder neuer installieren (Temurin von <https://adoptium.net/de/temurin/releases/> oder ein anderer Build).
2. `VectoraCloud-<version>-windows.zip` in einen eigenen Ordner entpacken, zum Beispiel `C:\Vectora`. Dieser Ordner wird das Home.
3. Eine normale Eingabeaufforderung oder PowerShell öffnen (nicht „Als Administrator ausführen“) und `start.bat` starten.

PowerShell blockiert mit der Standard-Ausführungsrichtlinie Skripte aus dem Internet. Entweder einmal `Unblock-File .\start.ps1` ausführen oder mit `powershell -ExecutionPolicy Bypass -File .\start.ps1` starten. `start.bat` braucht beides nicht.

Strg+C am Prompt fragt erst nach und stoppt alles beim zweiten Strg+C (siehe [console.de.md](console.de.md)). Danach fragt die Eingabeaufforderung eventuell „Batchvorgang abbrechen (J/N)?“, das spielt dann keine Rolle mehr.

Für den Dauerbetrieb ein eigenes lokales Konto ohne Administratorrechte nehmen, zum Beispiel mit der Aufgabenplanung („Unabhängig von der Benutzeranmeldung ausführen“, Trigger „Beim Start“). Einen Wrapper für einen Windows-Dienst liefert das Repository nicht mit.

## Linux und VPS mit systemd

```sh
# einmalig als root
useradd --system --user-group --home-dir /opt/vectora --create-home --shell /usr/sbin/nologin vectora
tar -xzf VectoraCloud-<version>-linux-macos.tar.gz
install -o vectora -g vectora -m 0644 VectoraCloud-<version>-linux-macos/VectoraCloud.jar /opt/vectora/
install -o vectora -g vectora -m 0755 VectoraCloud-<version>-linux-macos/start.sh /opt/vectora/
install -o root -g root -m 0644 vectora-cloud.service /etc/systemd/system/
systemctl daemon-reload

# erster Start mit Fragen, als Benutzer vectora; mit "shutdown" beenden
sudo -u vectora /opt/vectora/start.sh

systemctl enable --now vectora-cloud
journalctl -u vectora-cloud -f
```

Java: ein beliebiges Java 21+ im `PATH`, zum Beispiel `openjdk-21-jre-headless` der Distribution oder Temurin aus dem Adoptium-Repository. Für ein Java außerhalb des `PATH` setzt du `JAVA_HOME` in einem Drop-in (`systemctl edit vectora-cloud`):

```ini
[Service]
Environment=JAVA_HOME=/usr/lib/jvm/temurin-25-jre-amd64
Environment=VECTORA_XMX=1G
```

Statt der Fragen geht auch eine Setup-Datei: nach `/opt/vectora/setup.yml` legen und die zweite `ExecStart`-Zeile aus der Unit in einem Drop-in verwenden.

Was die Unit tut und warum:

- **Eigener Benutzer** `vectora`, nie root. Als root würde die Cloud ohnehin nicht starten.
- **Stoppen:** `KillMode=mixed` schickt SIGTERM nur an die Cloud. Die Cloud stoppt dann jeden Server mit seinem eigenen Stop-Befehl, wartet darauf, stoppt die verwaltete Datenbank zuletzt und beendet sich. Mit dem Standard `control-group` bekäme jeder Server im selben Moment SIGTERM. `TimeoutStopSec=5min` lässt dafür Zeit. Bei vielen Servern mit langem Speichern den Wert erhöhen. Exit-Code 143 nach SIGTERM gilt als Erfolg.
- **Neustart** nach einem Fehler nach 15 s, aber nicht bei Exit-Codes, die ein Neustart nicht behebt (2, 3, 4, 5, 64, 66), und höchstens fünfmal in zehn Minuten.
- **Härtung:** `NoNewPrivileges`, `ProtectSystem=strict` mit `ReadWritePaths=/opt/vectora` (Cloud und Server schreiben nur in ihr Home), `ProtectHome`, `PrivateTmp`, `PrivateDevices`, Schutz für Kernel und Control Groups, leere Capabilities, `SystemCallFilter=@system-service` und `UMask=0027`. `MemoryDenyWriteExecute` fehlt mit Absicht, weil der JIT-Compiler der JVM schreib- und ausführbaren Speicher braucht. `systemd-analyze security vectora-cloud` zeigt den Exposure-Wert.
- **MariaDB-Socket und `PrivateTmp`:** Die verwaltete MariaDB legt ihren Socket ins Temp-Verzeichnis. `PrivateTmp` gibt der ganzen Unit ein gemeinsames privates `/tmp` für Cloud, Server und MariaDB, der Socket funktioniert zwischen ihnen also weiter. Die Server verbinden sich ohnehin per TCP über `127.0.0.1`. Eine externe MariaDB auf demselben Rechner wird ebenfalls per TCP erreicht, das private `/tmp` versteckt ihren Socket also vor niemandem, der ihn braucht.
- **Grenzen:** `LimitNOFILE=65536` und `TasksMax=8192`. Jeder Thread jeder Server-JVM zählt als Task.

Unter systemd hat die Cloud keine Konsole: stdin ist leer, die Cloud läuft weiter. Befehle ohne Konsole kommen mit der API ([architecture.md](architecture.md)). Bis dahin die Cloud in `tmux` oder `screen` als Benutzer `vectora` starten, wenn du die Konsole brauchst.

Die CI installiert diese Unit genau so auf einem echten systemd, startet sie, wartet auf den Cloud-Kanal, prüft den Benutzer und stoppt sie wieder mit sauberem Ergebnis.

## macOS

1. Java 21 oder neuer installieren (Temurin-`.pkg` von Adoptium oder `brew install --cask temurin`).
2. Das tar entpacken (`tar -xzf VectoraCloud-<version>-linux-macos.tar.gz`), in den Ordner wechseln und `./start.sh` ausführen.

Für macOS veröffentlicht MariaDB keinen Server-Build. Der Modus `managed` fällt deshalb auf die eingebettete H2-Datenbank zurück und meldet das im Log ([database.md](database.md)). Für eine gemeinsame Datenbank `external` (zum Beispiel MariaDB aus Homebrew) oder Docker verwenden. Eine launchd-Datei liefert das Repository nicht mit.

## Docker

```sh
docker run -it --init -v vectora:/data -p 25565:25565 -p 19132:19132/udp \
  ghcr.io/vectode/vectora-cloud:<version>
```

Ohne Fragen:

```sh
docker run -d --init --name vectora -v vectora:/data -v ./setup.yml:/data/setup.yml:ro \
  -p 25565:25565 -p 19132:19132/udp --stop-timeout 300 \
  ghcr.io/vectode/vectora-cloud:<version> --non-interactive --setup-file /data/setup.yml
```

Das Image:

- wird in Stufen gebaut: Eine JDK-25-Stufe baut `VectoraCloud.jar` aus den Quellen, die Laufzeitstufe ist `eclipse-temurin:25-jre-noble`. Beide Basis-Images sind per Digest festgelegt, Dependabot hält sie aktuell.
- läuft als Benutzer `vectora` mit UID und GID 1000. `/data` ist das Home und ein Volume; alles darin (`config/`, `local/`, `database/`, `storage/`, `secrets/`, `state/`) liegt in diesem einen Volume, ein Update mit neuem Container behält es, und ein altes Volume wird beim ersten Start umgezogen ([Update](#was-wo-liegt-und-update-von-einem-alten-home)); dafür braucht es nur Platz für die Sicherungskopie der Konfiguration. Jar und Startskript in `/opt/vectora` gehören root und sind für die Cloud nur lesbar. Ein Bind-Mount statt eines Volumes muss UID 1000 gehören (`chown -R 1000:1000 ./data`).
- startet über `start.sh`. Argumente nach dem Image-Namen gehen an die Cloud, `VECTORA_XMX` setzt den Heap der Cloud.
- stoppt mit SIGTERM. Gib ihm Zeit mit `--stop-timeout 300` (oder `stop_grace_period` in compose), sonst beendet Docker die Server nach 10 s hart.
- hat einen Healthcheck ohne curl oder netcat: bash öffnet eine TCP-Verbindung zum Cloud-Kanal auf `127.0.0.1:7010` (`VECTORA_HEALTHCHECK_PORT`, falls du ihn in `config/nodes.yml` verlegt hast) und schließt sie vor dem Handshake-Timeout des Kanals. Dabei entsteht keine Log-Zeile.
- braucht keinen Docker-Socket, keine Capability und keinen privilegierten Modus. Der Datenbankmodus `docker` ist für eine Cloud auf einem Rechner mit Docker gedacht. Im Container `managed`, `external` (wie in compose unten) oder `h2` verwenden.
- gibt es für `linux/amd64` und `linux/arm64`. Unter arm64 fällt `managed` auf H2 zurück.

`--init` (oder `init: true` in compose) setzt einen kleinen Init-Prozess vor Java, der Prozesse aufräumt, die die Server hinterlassen.

Selbst bauen aus einem Checkout (der Build-Kontext ist das Repository-Wurzelverzeichnis):

```sh
docker build -f cloud/dist/docker/Dockerfile -t vectora-cloud .
```

### Docker Compose mit MariaDB

[`compose.yml`](../../cloud/dist/docker/compose.yml) startet die Cloud und MariaDB 12.3 (die aktuelle Long-Term-Support-Reihe, dieselbe, die der Managed-Modus lädt) als externe Datenbank. Ein Datenvolume aus einer früheren Compose-Datei mit MariaDB 11.8 wird beim ersten Start aktualisiert (`MARIADB_AUTO_UPGRADE`); vorher ein Backup anlegen, denn die Dateien lassen sich nicht auf 11.8 zurückstellen:

```sh
cd cloud/dist/docker
cp setup.example.yml setup.yml          # eula: true setzen, wenn du die Minecraft-EULA akzeptierst
mkdir -p secrets
openssl rand -base64 32 | tr -d '\n' > secrets/db_password
openssl rand -base64 32 | tr -d '\n' > secrets/db_root_password
chmod 0644 secrets/db_password          # die Cloud läuft als UID 1000 und muss sie lesen können
docker compose up -d
docker compose logs -f cloud
```

- Passwörter sind Docker-Secrets (Dateien unter `/run/secrets/`), keine Umgebungsvariablen. Die würden in `docker inspect` und in der Umgebung jedes Servers auftauchen, den die Cloud startet. MariaDB liest sie über `MARIADB_PASSWORD_FILE`, die Cloud über `database.password-file` in `setup.yml`.
- MariaDB veröffentlicht keinen Port. Nur die Cloud erreicht sie über das Compose-Netz (Host `database`).
- Ports: Java 25565/tcp und Bedrock 19132/udp sind veröffentlicht. Der Legacy-Proxy (25564/tcp) ist auskommentiert, bis du ihn einschaltest.
- `stop_grace_period: 5m`, `pids_limit: 8192`, `no-new-privileges` und für die Cloud alle Capabilities entfernt.
- `secrets/` und `setup.yml` in diesem Ordner stehen in `.gitignore`.

## Pterodactyl

1. Im Admin-Bereich unter Nests [`egg-vectora-cloud.json`](../../cloud/dist/pterodactyl/egg-vectora-cloud.json) importieren (PTDL_v2).
2. Einen Server mit dem Egg anlegen. Er braucht **eine Allocation pro Port**: Die primäre Allocation ist der Java-Port, dazu eine für Bedrock (UDP) und, falls eingeschaltet, eine für den Legacy-Proxy. Diese Ports in die Variablen eintragen.
3. „Accept the Minecraft EULA“ auf `true` setzen, wenn du die EULA akzeptierst, dann den Server starten.

Der Installer (in `ghcr.io/pterodactyl/installers:alpine`) lädt `VectoraCloud.jar` des gewählten Releases (`latest` ist das neueste Release einschließlich Vorabversionen) und prüft es gegen `SHA256SUMS` des Releases. Stimmt die Prüfsumme nicht, bricht er ab und behält nichts. Der Server läuft im Java-Image von Pterodactyl (`yolks:java_25` oder `java_21`) als unprivilegierter Benutzer des Panels, die root-Prüfung besteht er also.

So funktioniert das Egg:

| Teil | Wert |
|---|---|
| Startbefehl | `java -Xms128M -Xmx{{CLOUD_HEAP}} -jar VectoraCloud.jar --non-interactive --setup-file setup.yml --lang {{CLOUD_LANGUAGE}}` |
| Setup-Datei | Wings schreibt `setup.yml` vor jedem Start aus den Variablen (`config.files`, Parser `yaml`): `schema-version`, `eula`, `language`, `network-name`, `network.bind`, `network.java-port` (die primäre Allocation), `network.bedrock-port`, `network.legacy-port`, `servers.legacy-proxy`, `database.mode`. Sie zählt nur für das erste Setup. |
| Gestartet, sobald | im Log `Cloud channel listens on` steht |
| Stoppen | der Konsolenbefehl `shutdown`: Jeder Server bekommt seinen Stop-Befehl, danach endet die Cloud |
| Variablen | Version, Heap der Cloud, EULA, Sprache, Netzwerkname, Bedrock-Port, Legacy-Proxy an/aus, Port des Legacy-Proxys, Datenbankmodus |

- **Datenbank:** `managed` (die Cloud betreibt eine eigene MariaDB im Server; läuft unter x86_64, weil die Pterodactyl-Images auf glibc aufbauen), `external` (eine Datenbank aus dem Datenbank-Tab des Panels) oder `h2`. Den Modus `docker` bietet das Egg nicht an, denn der Server ist ein Container ohne Docker, und das Setup lehnt ihn dort ebenfalls ab. Für `external` im Dateimanager `setup.yml` öffnen, unter `database:` die Schlüssel `host`, `port`, `name`, `user` und `password-file: db-password.txt` ergänzen und das Passwort in `db-password.txt` schreiben. Wings ersetzt nur die Schlüssel, die es selbst verwaltet, und lässt die anderen stehen. Alternativ `VECTORA_DB_PASSWORD=...` in `secrets/vectora.env` eintragen ([setup.md](setup.md#pterodactyl)).
- **Dateien:** Der Dateimanager des Servers zeigt das ganze Home: `config/` für die Konfiguration, `local/services/<id>` für die Welten, `logs/cloud.log` für das Log. Eine Egg-Installation aus der Zeit vor dem neuen Aufbau wird beim ersten Start der neuen Version umgezogen; stoppe den Server vorher im Panel, damit kein Minecraft-Server mehr läuft.
- **Arbeitsspeicher:** Das Speicherlimit des Servers gilt für die Cloud und jeden Minecraft-Server, den sie startet. Das Setup warnt, wenn die Heaps zusammen mehr als `SERVER_MEMORY` ergeben. Den Heap der Cloud klein halten (512M) und den Rest den Servern geben.
- **PID-Limit:** Wings begrenzt jeden Server-Container standardmäßig auf **512 Prozesse und Threads** (`docker.container_pid_limit` in `/etc/pterodactyl/config.yml`). Eine Minecraft-Server-JVM nutzt grob 50 bis 150 Threads, Proxy und MariaDB mehr. Mehrere Server in einem Pterodactyl-Server stoßen deshalb an diese Grenze und scheitern dann mit „unable to create native thread“ oder `OutOfMemoryError`. In der Wings-Konfiguration erhöhen (zum Beispiel auf 4096) und Wings neu starten. Dafür braucht man Zugriff auf den Node, nicht nur auf das Panel.
- **Weitere Grenzen:** nur ein Node (kein Cluster), und jeder Port braucht eine Allocation. Einen Netzwerknamen nur aus Ziffern oder `true`/`false` würde Wings in eine Zahl oder einen Wahrheitswert umwandeln. Die Regel der Variable verlangt deshalb mindestens einen Buchstaben.

## Ein Release prüfen

Jede Datei eines Releases ist mit cosign keyless signiert (Sigstore, die GitHub-Actions-Identität von `release.yml`). Die drei Dateien aus `SHA256SUMS` haben zusätzlich eine Build-Provenance-Attestierung von GitHub (SLSA-Provenance, mit Sigstore signiert). `vectora.intoto.jsonl` ist dieselbe Attestierung als Datei, sie lässt sich also auch ohne Abfrage bei GitHub prüfen; `SHA256SUMS` selbst sichert seine Signatur ab. Ersetze `v0.2.0` durch den Tag, den du heruntergeladen hast.

Das Repository heißt seit dem 29.10.2026 `VectoDE/VectoraCloud` statt `VectoDE/Vectora-Network`. Signaturen und Provenance halten den Repository-Namen zum Zeitpunkt des Builds fest, deshalb gelten je nach Release unterschiedliche Namen:

- **v0.1.0** ist als `VectoDE/Vectora-Network` signiert. Nimm als Zertifikats-Identität `https://github.com/VectoDE/Vectora-Network/.github/workflows/release.yml@refs/tags/v0.1.0`, `gh attestation verify ... --repo VectoDE/Vectora-Network` und `slsa-verifier ... --source-uri github.com/VectoDE/Vectora-Network`. v0.1.0 enthält die Plugin-Jars und `SHA256SUMS`, kein `VectoraCloud.jar`.
- **Alle späteren Releases** sind als `VectoDE/VectoraCloud` signiert, wie in den Befehlen unten.

Bis v0.1.0 stammt `vectora.intoto.jsonl` aus slsa-github-generator und wird mit `slsa-verifier verify-artifact ... --provenance-path vectora.intoto.jsonl --source-uri github.com/VectoDE/Vectora-Network --source-tag v0.1.0` geprüft. Spätere Releases erzeugen die Provenance mit `actions/attest-build-provenance` im Build-Job: Der SLSA-Generator lässt sich nur über einen Tag einbinden, der Release-Workflow pinnt aber jede Action auf einen Commit-SHA. Die Datei ist dann ein Sigstore-Bundle, das `gh attestation verify --bundle` liest.

```sh
# 1. Prüfsummen (nur Integrität: SHA256SUMS kommt vom selben Ort)
sha256sum --check --ignore-missing SHA256SUMS

# 2. cosign: signiert von release.yml dieses Repositorys bei diesem Tag
cosign verify-blob VectoraCloud.jar \
  --bundle VectoraCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectoraCloud/.github/workflows/release.yml@refs/tags/v0.2.0" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# 3. GitHub-Attestierung (Build-Provenance)
gh attestation verify VectoraCloud.jar --repo VectoDE/VectoraCloud

# 4. Dieselbe Provenance aus der Release-Datei, ohne Abruf bei GitHub
gh attestation verify VectoraCloud.jar --repo VectoDE/VectoraCloud \
  --bundle vectora.intoto.jsonl \
  --signer-workflow VectoDE/VectoraCloud/.github/workflows/release.yml \
  --source-ref refs/tags/v0.2.0
```

Das Container-Image, immer über Digest oder Tag eines Releases:

```sh
cosign verify ghcr.io/vectode/vectora-cloud:0.2.0 \
  --certificate-identity "https://github.com/VectoDE/VectoraCloud/.github/workflows/release.yml@refs/tags/v0.2.0" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

gh attestation verify oci://ghcr.io/vectode/vectora-cloud:0.2.0 --repo VectoDE/VectoraCloud

# SBOM und BuildKit-Provenance, die mit dem Image gespeichert sind
docker buildx imagetools inspect ghcr.io/vectode/vectora-cloud:0.2.0 --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/vectode/vectora-cloud:0.2.0 --format '{{ json .Provenance }}'
```

Ob das Image das veröffentlichte Jar enthält: `docker run --rm --entrypoint sha256sum ghcr.io/vectode/vectora-cloud:0.2.0 /opt/vectora/VectoraCloud.jar` muss die Zeile aus `SHA256SUMS` ausgeben.

## Was wo getestet wird

| Teil | In der CI getestet ([operations.yml](../../.github/workflows/operations.yml)) | Zeigt erst ein echtes Release oder eine echte Installation |
|---|---|---|
| Startskripte | Linux, macOS, Windows (PowerShell 5.1), alle Fälle oben | |
| Release-Archive | mit `package.sh` gebaut, zweimal gebaut und verglichen, entpackt und gestartet | den Upload ins Release |
| systemd-Unit | `systemd-analyze verify`, echter Start und Stopp unter Ubuntu | lang laufende Server und die verwaltete MariaDB unter der Härtung |
| Image | aus den Quellen und über den Release-Weg, amd64 und arm64, Start als UID 1000, root abgelehnt, Healthcheck, sauberes `docker stop` | Push nach GHCR, cosign-Signatur, Attestierungen, die `latest`-Regel |
| compose.yml | `docker compose config` | einen vollständigen Start mit MariaDB |
| Egg | Struktur, Variablen, Regeln gegen ihre Standardwerte, die Setup-Datei, die Wings schreiben würde, ShellCheck des Installers | Import in ein Panel, den Download des Installers, einen Lauf unter Wings |
| Release-Workflow | actionlint | Signieren, Provenance und Veröffentlichen bei einem Tag |
