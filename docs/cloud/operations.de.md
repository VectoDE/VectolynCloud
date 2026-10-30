# VectolynCloud betreiben

English: [operations.md](operations.md)

Diese Seite beschreibt, wie du `VectolynCloud.jar` unter Windows, Linux (VPS oder eigener Server, mit systemd), macOS, Docker und Pterodactyl betreibst und wie du prüfst, dass ein Download wirklich aus diesem Repository stammt. Wir hosten nichts: Jedes Netzwerk, das mit Vectolyn gebaut wird, läuft auf dem Rechner seines Betreibers.

Die Dateien liegen in [`cloud/dist/`](../../cloud/dist):

| Datei | Zweck |
|---|---|
| `start.sh` | Startskript für Linux und macOS (bash 3.2 oder neuer) |
| `start.bat`, `start.ps1` | Startskripte für Windows (Eingabeaufforderung, Windows PowerShell 5.1 oder PowerShell 7) |
| `systemd/vectolyn-cloud.service` | systemd-Unit mit eigenem Benutzer und Härtung |
| `docker/Dockerfile`, `docker/compose.yml`, `docker/setup.example.yml` | Container-Image und eine Compose-Datei mit MariaDB |
| `pterodactyl/egg-vectolyn-cloud.json` | Pterodactyl-Egg |
| `package.sh` | baut die Release-Archive (nutzt der Release-Workflow) |

## Was ein Release enthält

Der Release-Workflow ([`release.yml`](../../.github/workflows/release.yml)) läuft bei einem `v*`-Tag und hängt an:

| Datei | Inhalt |
|---|---|
| `VectolynCloud.jar` | die Cloud, für `java -jar`, Docker und Pterodactyl |
| `VectolynCloud-<version>-windows.zip` | `VectolynCloud.jar`, `start.bat`, `start.ps1` |
| `VectolynCloud-<version>-linux-macos.tar.gz` | `VectolynCloud.jar`, `start.sh` (ausführbar) |
| `VectolynCloud-<version>-banner.png`, `VectolynCloud-<version>-banner-2x.png` | das [Release-Banner](#release-banner) oben in den Release Notes, 1280 x 320 und 2560 x 640 |
| `SHA256SUMS` | SHA-256 der fünf Dateien darüber |
| `<datei>.sigstore.json` | cosign-Signatur-Bundle für jede Datei und für `SHA256SUMS` |
| `vectolyn.intoto.jsonl` | SLSA-Build-Provenance für jede Datei aus `SHA256SUMS`, als Sigstore-Bundle der GitHub-Attestierung |

Bevor ein Tag gepusht wird, und am Ende jeder Etappe, läuft die Aufräum-Prüfung aus [docs/openssf.de.md](../openssf.de.md#die-aufräum-prüfung): keine offenen Pull Requests, nichts Behebbares mehr unter *Security and quality*, und CI, CodeQL, Dependency Review und Scorecard laufen auf `main` erneut, bis das gilt.

Die Startskripte kommen als ein Archiv pro Betriebssystem, nicht als lose Dateien. Ein heruntergeladenes `start.sh` verliert sein Ausführungsrecht, im tar bleibt es erhalten. Das zip behält die CRLF-Zeilenenden, die die Windows-Skripte brauchen. Und jedes Archiv ist ein einziger Download mit dem passenden Jar. Das Jar hängt zusätzlich einzeln am Release. Die Archive sind reproduzierbar (feste Reihenfolge, feste Besitzer und Zeitstempel), derselbe Tag ergibt also beim zweiten Bauen dieselbe SHA-256.

Das Container-Image landet unter `ghcr.io/vectode/vectolyn-cloud` mit der Version als Tag (`1.0.0`, ohne `v`). `latest` wandert nur mit Releases, die keine Vorabversionen sind, also mit stabilen und `-lts`-Tags; `-dev`, `-alpha`, `-beta` und `-rc` lassen es stehen. Jedes `-dev`-Release verschiebt zusätzlich das Tag `dev` (immer das neueste `-dev`-Release; es ist bis zum ersten stabilen Release der Standard in `compose.yml`, danach wechselt der Standard auf `latest`) (siehe [Versionen und Tags](#versionen-und-tags)). Im Image steckt genau das `VectolynCloud.jar` des Releases: Der Workflow übergibt das signierte Jar an den Image-Build, statt es neu zu kompilieren, und prüft vorher seine SHA-256 gegen `SHA256SUMS`.

## Versionen und Tags

Ein Release beginnt mit einem gepushten Tag. Der erste Schritt von `release.yml` prüft ihn, bevor irgendetwas gebaut oder signiert wird, und bricht das Release mit einer Meldung ab, die die verletzte Regel nennt.

| Tag | Erlaubt | Badge im Banner | Auf GitHub |
|---|---|---|---|
| `v0.2.0-dev`, `v0.2.0-dev.3` | jede Version; unter 1.0.0 die einzige Form | PRE-RELEASE, grau | Vorabversion |
| `v1.0.0-alpha`, `v1.0.0-alpha.1` | ab 1.0.0 | ALPHA, rot | Vorabversion |
| `v1.0.0-beta.2` | ab 1.0.0 | BETA, gold | Vorabversion |
| `v1.0.0-rc.1` | ab 1.0.0 | RC, cyan | Vorabversion |
| `v1.4.0-lts` | ab 1.0.0 | LTS, grün | Release, wird Latest |
| `v1.0.0` | ab 1.0.0 | keins | Release, wird Latest |

- `MAJOR`, `MINOR`, `PATCH` und der Zähler `.N` sind Zahlen ohne führende Nullen. Der Zähler ist optional.
- `-lts` hat keinen Zähler. Eine LTS-Linie zählt ihre Releases mit der Patch-Version: `v1.4.0-lts`, `v1.4.1-lts`.
- Alles andere bricht das Release ab: eine 0.x-Version ohne `-dev` oder mit anderem Zusatz (`v0.2.0`, `v0.2.0-alpha.1`), ein unbekannter Zusatz (`-nightly`, `-RC.1`), Build-Metadaten (`+build.7`; GitHub kann das `+` in Dateinamen umschreiben) und kaputte Tags (`v1.0`, `v01.0.0`).
- Das Image-Tag `latest` folgt derselben Einordnung: Stabile und `-lts`-Releases verschieben es, Vorabversionen nicht. Das Tag `dev` ist getrennt: Nur `-dev`-Releases verschieben es, auf das neueste. Es zeigt auf denselben Digest wie das Versions-Tag, Signatur und Provenance dieses Digests gelten also auch dafür.

Die Regeln stehen an einer Stelle, in `ReleaseVersion` in [`tools/release-banner`](../../tools/release-banner); das Banner liest den Tag mit demselben Code. Einen Tag vor dem Pushen prüfen:

```sh
bash tools/release-banner/check-tag.sh v1.0.0-rc.1
# channel=rc
# prerelease=true
```

Ein Tag, der eine Regel verletzt, gibt den Grund aus und endet mit einem Fehler. Wurde so ein Tag schon gepusht: löschen (`git push --delete origin <tag>` und `git tag -d <tag>`), korrigieren, neu pushen.

## Voraussetzungen

- **Java 21 oder neuer** für die Cloud selbst. Die Server, die sie startet, bekommen die Java-Version, die ihre Minecraft-Version braucht. Passt kein installiertes Java, lädt die Cloud ein Temurin-JRE nach `<home>/storage/jre/` (siehe [node.md](node.md#java-runtimes)).
- Ein normales Benutzerkonto. Als root oder Administrator startet die Cloud nicht (siehe [unten](#nie-als-root-oder-administrator)).
- Arbeitsspeicher: der Heap der Cloud (`VECTOLYN_XMX`, Standard 512M) plus der Heap jedes Servers, den sie betreibt.
- **Internetzugang beim ersten Start einer Gruppe.** Dann lädt die Cloud die Server-Software (Paper, Velocity, Fabric, PandaSpigot) und die Plugins und Mods aus dem Katalog: Geyser und Floodgate für Bedrock, ViaVersion, ViaBackwards und ViaRewind, damit Java-Clients von 1.8 bis 26.3 beitreten können, dazu LuckPerms und die Performance-Mods für Fabric. Jede Datei wird gegen ihre Prüfsumme geprüft und in `config/software.lock.yml` festgehalten. Spätere Starts nehmen sie ohne Netz aus `storage/artifacts/` ([download.md](download.md#what-the-node-installs)).

## Erster Start: was in jedem Server landet

Die Vorlagen stecken im Jar: Für jeden Server, der gewünscht ist, schreibt der erste Start `local/templates/<Server>/default/`. Einen Ordner, den du schon hast, rührt die Cloud nicht an ([node.md](node.md#built-in-templates)). Ein abgeschalteter Server hinterlässt keinen Vorlagenordner.

Vor jedem Start füllt der Node den Ordner des Servers: das Server-Jar, die Plugins (bei Fabric die Mods), die seine Gruppe braucht, und die VectolynCloud-Bridge seiner Plattform. Was er dort ablegt, notiert er in `plugins/.vectolyn-managed` (bei Fabric `mods/`). Will die Gruppe eine dieser Dateien nicht mehr, entfernt er sie wieder. Plugins, die du selbst hineinkopierst, bleiben liegen. Ein PandaSpigot-Server (1.8.8) startet nicht ohne seine Bridge, denn nur sie prüft den Token des Legacy-Proxys. Einzelheiten: [node.md](node.md#server-software).

## Startskripte

```sh
./start.sh [Argumente]                 # Linux, macOS
start.bat [Argumente]                  # Windows, Eingabeaufforderung
powershell -ExecutionPolicy Bypass -File .\start.ps1 [Argumente]   # Windows PowerShell
```

Alle Argumente gehen unverändert an `VectolynCloud.jar`: `--non-interactive`, `--setup-file <datei>`, `--lang de|en`, `--allow-root`, `--home <pfad>`, `--help`, `--version` ([launcher.md](launcher.md#arguments)).

| Variable | Standard | Bedeutung |
|---|---|---|
| `VECTOLYN_HOME` | Ordner des Skripts | Cloud-Home. Das Skript wechselt hinein, relative Pfade in Argumenten (`--setup-file setup.yml`) gelten also relativ zum Home. |
| `VECTOLYN_JAR` | `VectolynCloud.jar` neben dem Skript | Pfad zum Jar |
| `VECTOLYN_XMX` | `512M` | maximaler Heap des Cloud-Prozesses, Ziffern plus `K`, `M` oder `G` |
| `JAVA_HOME` | (nicht gesetzt) | zu verwendendes Java. Fehlt darin `bin/java`, sagt das Skript das und nimmt `java` aus dem `PATH`. |
| `JDK_JAVA_OPTIONS` | (nicht gesetzt) | weitere JVM-Optionen, liest der `java`-Launcher selbst |

So wird Java gefunden: zuerst `JAVA_HOME`, dann der `PATH`. Gibt es gar kein Java, nennt das Skript die Bezugsquelle (<https://adoptium.net/de/temurin/releases/>) und endet mit 3. Die Version prüft `VectolynCloud.jar` selbst. Seine erste Klasse ist für Java 8 kompiliert, deshalb melden Java 8 bis 20 „VectolynCloud braucht Java 21 oder neuer“ samt gefundener Version und Pfad statt eines Klassendatei-Fehlers. Aus demselben Grund geben die Skripte keine JVM-Option mit, die alte Java-Versionen ablehnen. Den nativen Zugriff für die Konsole erlaubt das Manifest des Jars (`Enable-Native-Access: ALL-UNNAMED`).

Sicherheit der Skripte:

- `start.sh` läuft mit `set -euo pipefail`, setzt jede Expansion in Anführungszeichen und startet Java mit `exec`, damit Java die Signale direkt bekommt (systemd, `docker stop`). Es besteht ShellCheck und läuft mit der bash 3.2 von macOS.
- `start.bat` liest die Umgebung mit verzögerter Expansion und entfernt Anführungszeichen. `&`, `|`, `<`, `>` oder Klammern in einer Variable werden deshalb nie als Befehl ausgeführt. Die Argumente gehen mit ausgeschalteter verzögerter Expansion weiter, ein `!` darin bleibt also erhalten.
- `start.ps1` kommt ohne `Invoke-Expression` und ohne Befehls-String aus, die Argumente gehen als Array an Java. Es besteht PSScriptAnalyzer.
- Keines der Skripte nutzt `sudo` oder fordert Admin-Rechte an.

Der CI-Workflow [`operations.yml`](../../.github/workflows/operations.yml) führt jedes Skript unter Linux, macOS und Windows aus: Version und Hilfe, ein zu altes Java 17, gar kein Java, ein `JAVA_HOME` ohne Java, ein fehlendes Jar, ein kaputtes `VECTOLYN_XMX`, Werte mit `&` und Anführungszeichen in der Umgebung, ein Ordnername mit Leerzeichen, Klammern, `&` und `!`, ein Start als root mit und ohne `--allow-root` und die Skripte aus den Release-Archiven.

### Exit-Codes

| Code | Bedeutung |
|---|---|
| 0 | normal beendet, oder `--help` / `--version` |
| 1 | der Node hat beim Start eine Ausnahme geworfen |
| 2 | als root oder Administrator gestartet, ohne `--allow-root` |
| 3 | kein Java gefunden, oder Java älter als 21 |
| 4 | eine eingebettete Bibliothek fehlt oder hat die falsche SHA-256 |
| 5 | kein Node-Einstiegspunkt in diesem Build |
| 64 | ungültiges Argument, oder `VECTOLYN_XMX` ist keine Größe |
| 66 | `VectolynCloud.jar` oder der Home-Ordner fehlt (Startskripte) |
| 143 | durch SIGTERM beendet (systemd, `docker stop`), nach dem sauberen Herunterfahren |

### Nie als root oder Administrator

Die Cloud lädt Server-Software herunter und führt sie aus. Mit root- oder Administratorrechten würde ein Fehler oder ein manipulierter Download das ganze System treffen. Der Launcher verweigert so einen Start deshalb mit Exit-Code 2 und erklärt, wie es richtig geht ([launcher.md](launcher.md#root-and-administrator-check)). `--allow-root` oder `VECTOLYN_ALLOW_ROOT=true` erlauben ihn trotzdem und warnen bei jedem Start. Nutze das nur, wenn es wirklich keinen anderen Weg gibt.

## Erster Start: das Setup

Solange in `cloud.yml` nicht `setup-complete: true` steht, startet die Cloud keinen Server. Mit Terminal stellt sie ihre Fragen. Ohne Terminal (systemd, Docker ohne `-it`, Pterodactyl) braucht sie eine Setup-Datei:

```sh
./start.sh --non-interactive --setup-file setup.yml
```

Format und Regeln: [setup.md](setup.md). Die Datei enthält kein Secret. Ein Datenbank-Passwort kommt aus einer Datei (`database.password-file`) oder aus `secrets/vectolyn.env`. Ist das Setup abgeschlossen, wird die Datei ignoriert.

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

**Windows-Konsole.** `start.bat` und `start.ps1` stellen die Konsole auf UTF-8 um (`chcp 65001`) und starten Java mit `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`, damit Umlaute im Log stimmen. Startest du `java -jar VectolynCloud.jar` von Hand in einer alten Konsole, setze dasselbe.

**Logs.** `logs/cloud.log` ist das Log der Cloud selbst, in UTF-8 und mit vollen Stacktraces (Secrets geschwärzt). Jeder Start beginnt eine neue Datei, die letzten fünf bleiben als `cloud.1.log` bis `cloud.5.log`. Das Log jedes Servers ist `logs/services/<id>.log`. Was ein Start auf dem Bildschirm zeigt, steht in [node.md](node.md#start-output).

## Windows

1. Java 21 oder neuer installieren (Temurin von <https://adoptium.net/de/temurin/releases/> oder ein anderer Build).
2. `VectolynCloud-<version>-windows.zip` in einen eigenen Ordner entpacken, zum Beispiel `C:\Vectolyn`. Dieser Ordner wird das Home.
3. Eine normale Eingabeaufforderung oder PowerShell öffnen (nicht „Als Administrator ausführen“) und `start.bat` starten.

PowerShell blockiert mit der Standard-Ausführungsrichtlinie Skripte aus dem Internet. Entweder einmal `Unblock-File .\start.ps1` ausführen oder mit `powershell -ExecutionPolicy Bypass -File .\start.ps1` starten. `start.bat` braucht beides nicht.

Strg+C am Prompt fragt erst nach und stoppt alles beim zweiten Strg+C (siehe [console.de.md](console.de.md)). Danach fragt die Eingabeaufforderung eventuell „Batchvorgang abbrechen (J/N)?“, das spielt dann keine Rolle mehr.

Für den Dauerbetrieb ein eigenes lokales Konto ohne Administratorrechte nehmen, zum Beispiel mit der Aufgabenplanung („Unabhängig von der Benutzeranmeldung ausführen“, Trigger „Beim Start“). Einen Wrapper für einen Windows-Dienst liefert das Repository nicht mit.

## Linux und VPS mit systemd

```sh
# einmalig als root
useradd --system --user-group --home-dir /opt/vectolyn --create-home --shell /usr/sbin/nologin vectolyn
tar -xzf VectolynCloud-<version>-linux-macos.tar.gz
install -o vectolyn -g vectolyn -m 0644 VectolynCloud-<version>-linux-macos/VectolynCloud.jar /opt/vectolyn/
install -o vectolyn -g vectolyn -m 0755 VectolynCloud-<version>-linux-macos/start.sh /opt/vectolyn/
install -o root -g root -m 0644 vectolyn-cloud.service /etc/systemd/system/
systemctl daemon-reload

# erster Start mit Fragen, als Benutzer vectolyn; mit "shutdown" beenden
sudo -u vectolyn /opt/vectolyn/start.sh

systemctl enable --now vectolyn-cloud
journalctl -u vectolyn-cloud -f
```

Java: ein beliebiges Java 21+ im `PATH`, zum Beispiel `openjdk-21-jre-headless` der Distribution oder Temurin aus dem Adoptium-Repository. Für ein Java außerhalb des `PATH` setzt du `JAVA_HOME` in einem Drop-in (`systemctl edit vectolyn-cloud`):

```ini
[Service]
Environment=JAVA_HOME=/usr/lib/jvm/temurin-25-jre-amd64
Environment=VECTOLYN_XMX=1G
```

Statt der Fragen geht auch eine Setup-Datei: nach `/opt/vectolyn/setup.yml` legen und die zweite `ExecStart`-Zeile aus der Unit in einem Drop-in verwenden.

Was die Unit tut und warum:

- **Eigener Benutzer** `vectolyn`, nie root. Als root würde die Cloud ohnehin nicht starten.
- **Stoppen:** `KillMode=mixed` schickt SIGTERM nur an die Cloud. Die Cloud stoppt dann jeden Server mit seinem eigenen Stop-Befehl, wartet darauf, stoppt die verwaltete Datenbank zuletzt und beendet sich. Mit dem Standard `control-group` bekäme jeder Server im selben Moment SIGTERM. `TimeoutStopSec=5min` lässt dafür Zeit. Bei vielen Servern mit langem Speichern den Wert erhöhen. Exit-Code 143 nach SIGTERM gilt als Erfolg.
- **Neustart** nach einem Fehler nach 15 s, aber nicht bei Exit-Codes, die ein Neustart nicht behebt (2, 3, 4, 5, 64, 66), und höchstens fünfmal in zehn Minuten.
- **Härtung:** `NoNewPrivileges`, `ProtectSystem=strict` mit `ReadWritePaths=/opt/vectolyn` (Cloud und Server schreiben nur in ihr Home), `ProtectHome`, `PrivateTmp`, `PrivateDevices`, Schutz für Kernel und Control Groups, leere Capabilities, `SystemCallFilter=@system-service` und `UMask=0027`. `MemoryDenyWriteExecute` fehlt mit Absicht, weil der JIT-Compiler der JVM schreib- und ausführbaren Speicher braucht. `systemd-analyze security vectolyn-cloud` zeigt den Exposure-Wert.
- **MariaDB-Socket und `PrivateTmp`:** Die verwaltete MariaDB legt ihren Socket ins Temp-Verzeichnis. `PrivateTmp` gibt der ganzen Unit ein gemeinsames privates `/tmp` für Cloud, Server und MariaDB, der Socket funktioniert zwischen ihnen also weiter. Die Server verbinden sich ohnehin per TCP über `127.0.0.1`. Eine externe MariaDB auf demselben Rechner wird ebenfalls per TCP erreicht, das private `/tmp` versteckt ihren Socket also vor niemandem, der ihn braucht.
- **Grenzen:** `LimitNOFILE=65536` und `TasksMax=8192`. Jeder Thread jeder Server-JVM zählt als Task.

Unter systemd hat die Cloud keine Konsole: stdin ist leer, die Cloud läuft weiter. Befehle ohne Konsole kommen mit der API ([architecture.md](architecture.md)). Bis dahin die Cloud in `tmux` oder `screen` als Benutzer `vectolyn` starten, wenn du die Konsole brauchst.

Die CI installiert diese Unit genau so auf einem echten systemd, startet sie, wartet auf den Cloud-Kanal, prüft den Benutzer und stoppt sie wieder mit sauberem Ergebnis.

## macOS

1. Java 21 oder neuer installieren (Temurin-`.pkg` von Adoptium oder `brew install --cask temurin`).
2. Das tar entpacken (`tar -xzf VectolynCloud-<version>-linux-macos.tar.gz`), in den Ordner wechseln und `./start.sh` ausführen.

Für macOS veröffentlicht MariaDB keinen Server-Build. Der Modus `managed` fällt deshalb auf die eingebettete H2-Datenbank zurück und meldet das im Log ([database.md](database.md)). Für eine gemeinsame Datenbank `external` (zum Beispiel MariaDB aus Homebrew) oder Docker verwenden. Eine launchd-Datei liefert das Repository nicht mit.

## Docker

```sh
docker run -it --init -v vectolyn:/data -p 25565:25565 -p 19132:19132/udp \
  ghcr.io/vectode/vectolyn-cloud:<version>
```

Ohne Fragen:

```sh
docker run -d --init --name vectolyn -v vectolyn:/data -v ./setup.yml:/data/setup.yml:ro \
  -p 25565:25565 -p 19132:19132/udp --stop-timeout 300 \
  ghcr.io/vectode/vectolyn-cloud:<version> --non-interactive --setup-file /data/setup.yml
```

Das Image:

- wird in Stufen gebaut: Eine JDK-25-Stufe baut `VectolynCloud.jar` aus den Quellen, die Laufzeitstufe ist `eclipse-temurin:25-jre-noble`. Beide Basis-Images sind per Digest festgelegt, Dependabot hält sie aktuell.
- läuft als Benutzer `vectolyn` mit UID und GID 1000. `/data` ist das Home und ein Volume; alles darin (`config/`, `local/`, `database/`, `storage/`, `secrets/`, `state/`) liegt in diesem einen Volume, ein Update mit neuem Container behält es, und ein altes Volume wird beim ersten Start umgezogen ([Update](#was-wo-liegt-und-update-von-einem-alten-home)); dafür braucht es nur Platz für die Sicherungskopie der Konfiguration. Jar und Startskript in `/opt/vectolyn` gehören root und sind für die Cloud nur lesbar. Ein Bind-Mount statt eines Volumes muss UID 1000 gehören (`chown -R 1000:1000 ./data`).
- startet über `start.sh`. Argumente nach dem Image-Namen gehen an die Cloud, `VECTOLYN_XMX` setzt den Heap der Cloud.
- stoppt mit SIGTERM. Gib ihm Zeit mit `--stop-timeout 300` (oder `stop_grace_period` in compose), sonst beendet Docker die Server nach 10 s hart.
- hat einen Healthcheck ohne curl oder netcat: bash öffnet eine TCP-Verbindung zum Cloud-Kanal auf `127.0.0.1:7010` (`VECTOLYN_HEALTHCHECK_PORT`, falls du ihn in `config/nodes.yml` verlegt hast) und schließt sie vor dem Handshake-Timeout des Kanals. Dabei entsteht keine Log-Zeile.
- braucht keinen Docker-Socket, keine Capability und keinen privilegierten Modus. Der Datenbankmodus `docker` ist für eine Cloud auf einem Rechner mit Docker gedacht. Im Container `managed`, `external` (wie in compose unten) oder `h2` verwenden.
- gibt es für `linux/amd64` und `linux/arm64`. Unter arm64 fällt `managed` auf H2 zurück.

`--init` (oder `init: true` in compose) setzt einen kleinen Init-Prozess vor Java, der Prozesse aufräumt, die die Server hinterlassen.

Selbst bauen aus einem Checkout (der Build-Kontext ist das Repository-Wurzelverzeichnis):

```sh
docker build -f cloud/dist/docker/Dockerfile -t vectolyn-cloud .
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

1. Im Admin-Bereich unter Nests [`egg-vectolyn-cloud.json`](../../cloud/dist/pterodactyl/egg-vectolyn-cloud.json) importieren (PTDL_v2).
2. Einen Server mit dem Egg anlegen. Er braucht **eine Allocation pro Port**: Die primäre Allocation ist der Java-Port, dazu eine für Bedrock (UDP) und, falls eingeschaltet, eine für den Legacy-Proxy. Diese Ports in die Variablen eintragen.
3. „Accept the Minecraft EULA“ auf `true` setzen, wenn du die EULA akzeptierst, dann den Server starten.

Der Installer (in `ghcr.io/pterodactyl/installers:alpine`) lädt `VectolynCloud.jar` des gewählten Releases (`latest` ist das neueste Release einschließlich Vorabversionen) und prüft es gegen `SHA256SUMS` des Releases. Stimmt die Prüfsumme nicht, bricht er ab und behält nichts. Der Server läuft im Java-Image von Pterodactyl (`yolks:java_25` oder `java_21`) als unprivilegierter Benutzer des Panels, die root-Prüfung besteht er also.

So funktioniert das Egg:

| Teil | Wert |
|---|---|
| Startbefehl | `java -Xms128M -Xmx{{CLOUD_HEAP}} -jar VectolynCloud.jar --non-interactive --setup-file setup.yml --lang {{CLOUD_LANGUAGE}}` |
| Setup-Datei | Wings schreibt `setup.yml` vor jedem Start aus den Variablen (`config.files`, Parser `yaml`): `schema-version`, `eula`, `language`, `network-name`, `network.bind`, `network.java-port` (die primäre Allocation), `network.bedrock-port`, `network.legacy-port`, `servers.legacy-proxy`, `database.mode`. Sie zählt nur für das erste Setup. |
| Gestartet, sobald | im Log `Cloud channel listens on` steht |
| Stoppen | der Konsolenbefehl `shutdown`: Jeder Server bekommt seinen Stop-Befehl, danach endet die Cloud |
| Variablen | Version, Heap der Cloud, EULA, Sprache, Netzwerkname, Bedrock-Port, Legacy-Proxy an/aus, Port des Legacy-Proxys, Datenbankmodus |

- **Datenbank:** `managed` (die Cloud betreibt eine eigene MariaDB im Server; läuft unter x86_64, weil die Pterodactyl-Images auf glibc aufbauen), `external` (eine Datenbank aus dem Datenbank-Tab des Panels) oder `h2`. Den Modus `docker` bietet das Egg nicht an, denn der Server ist ein Container ohne Docker, und das Setup lehnt ihn dort ebenfalls ab. Für `external` im Dateimanager `setup.yml` öffnen, unter `database:` die Schlüssel `host`, `port`, `name`, `user` und `password-file: db-password.txt` ergänzen und das Passwort in `db-password.txt` schreiben. Wings ersetzt nur die Schlüssel, die es selbst verwaltet, und lässt die anderen stehen. Alternativ `VECTOLYN_DB_PASSWORD=...` in `secrets/vectolyn.env` eintragen ([setup.md](setup.md#pterodactyl)).
- **Dateien:** Der Dateimanager des Servers zeigt das ganze Home: `config/` für die Konfiguration, `local/services/<id>` für die Welten, `logs/cloud.log` für das Log. Eine Egg-Installation aus der Zeit vor dem neuen Aufbau wird beim ersten Start der neuen Version umgezogen; stoppe den Server vorher im Panel, damit kein Minecraft-Server mehr läuft.
- **Arbeitsspeicher:** Das Speicherlimit des Servers gilt für die Cloud und jeden Minecraft-Server, den sie startet. Das Setup warnt, wenn die Heaps zusammen mehr als `SERVER_MEMORY` ergeben. Den Heap der Cloud klein halten (512M) und den Rest den Servern geben.
- **PID-Limit:** Wings begrenzt jeden Server-Container standardmäßig auf **512 Prozesse und Threads** (`docker.container_pid_limit` in `/etc/pterodactyl/config.yml`). Eine Minecraft-Server-JVM nutzt grob 50 bis 150 Threads, Proxy und MariaDB mehr. Mehrere Server in einem Pterodactyl-Server stoßen deshalb an diese Grenze und scheitern dann mit „unable to create native thread“ oder `OutOfMemoryError`. In der Wings-Konfiguration erhöhen (zum Beispiel auf 4096) und Wings neu starten. Dafür braucht man Zugriff auf den Node, nicht nur auf das Panel.
- **Weitere Grenzen:** nur ein Node (kein Cluster), und jeder Port braucht eine Allocation. Einen Netzwerknamen nur aus Ziffern oder `true`/`false` würde Wings in eine Zahl oder einen Wahrheitswert umwandeln. Die Regel der Variable verlangt deshalb mindestens einen Buchstaben.

## Mehrere Nodes

Ein Cluster besteht aus einem Head und seinen Mitgliedern; Ablauf, Vertrauensmodell und Befehle stehen in [cluster.md](cluster.md) (Englisch). Für den Betrieb wichtig:

- **Datenbank:** Jeder Node braucht einen MariaDB-Server, den alle Nodes erreichen: `database.mode: external` mit einer Adresse im Cluster-Netz. Mit `h2` lehnt der Head Tokens und Beitritte ab. `managed` und `docker` binden MariaDB an 127.0.0.1, damit können nur Nodes auf derselben Maschine beitreten; die Bindung ans Cluster-Netz kommt mit dem nächsten Schritt des Clusters.
- **Gleiche Version:** Jeder Node läuft mit der VectolynCloud-Version des Heads. Ein Node mit anderer Version tritt nicht bei bzw. bekommt keine Sitzung, bis er aktualisiert ist.
- **Firewall:** Den Cluster-Port des Heads (standardmäßig TCP 7011) nur für die anderen Nodes öffnen. In Docker auch veröffentlichen (`-p 7011:7011`) und `cluster init` die Adresse des Hosts geben. Die Ports für Proxys und Backends über Nodes hinweg kommen mit dem nächsten Schritt des Clusters.
- **Backup:** `secrets/cluster/` des Heads enthält die Cluster-CA und gehört zum Backup von `secrets/`. Ohne sie kann kein Node mehr beitreten.
- **Node entfernen:** `cluster remove <node-id>` auf dem Head widerruft sein Zertifikat sofort. Ein neuer Beitritt braucht ein neues Token.
- **Pterodactyl:** als Cluster nicht getestet; der Cluster-Port bräuchte eine eigene Allocation.

## Ein Release prüfen

Jede Datei eines Releases ist mit cosign keyless signiert (Sigstore, die GitHub-Actions-Identität von `release.yml`). Die fünf Dateien aus `SHA256SUMS` (die Banner-Bilder eingeschlossen) haben zusätzlich eine Build-Provenance-Attestierung von GitHub (SLSA-Provenance, mit Sigstore signiert). `vectolyn.intoto.jsonl` ist dieselbe Attestierung als Datei, sie lässt sich also auch ohne Abfrage bei GitHub prüfen; `SHA256SUMS` selbst sichert seine Signatur ab. Ersetze `v0.2.0-dev` durch den Tag, den du heruntergeladen hast.

Jedes Release ist als `VectoDE/VectolynCloud` signiert, wie in den Befehlen unten. Die Provenance erzeugt `actions/attest-build-provenance` im Build-Job; der Release-Workflow pinnt jede Action auf einen Commit-SHA. Die Provenance-Datei ist ein Sigstore-Bundle, das `gh attestation verify --bundle` liest.

```sh
# 1. Prüfsummen (nur Integrität: SHA256SUMS kommt vom selben Ort)
sha256sum --check --ignore-missing SHA256SUMS

# 2. cosign: signiert von release.yml dieses Repositorys bei diesem Tag
cosign verify-blob VectolynCloud.jar \
  --bundle VectolynCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectolynCloud/.github/workflows/release.yml@refs/tags/v0.2.0-dev" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# 3. GitHub-Attestierung (Build-Provenance)
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud

# 4. Dieselbe Provenance aus der Release-Datei, ohne Abruf bei GitHub
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud \
  --bundle vectolyn.intoto.jsonl \
  --signer-workflow VectoDE/VectolynCloud/.github/workflows/release.yml \
  --source-ref refs/tags/v0.2.0-dev
```

Die Banner-Bilder prüfst du genauso, zum Beispiel:

```sh
cosign verify-blob VectolynCloud-0.2.0-dev-banner-2x.png \
  --bundle VectolynCloud-0.2.0-dev-banner-2x.png.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/VectolynCloud/.github/workflows/release.yml@refs/tags/v0.2.0-dev" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

gh attestation verify VectolynCloud-0.2.0-dev-banner-2x.png --repo VectoDE/VectolynCloud
```

Das Container-Image, immer über Digest oder Tag eines Releases:

```sh
cosign verify ghcr.io/vectode/vectolyn-cloud:0.2.0-dev \
  --certificate-identity "https://github.com/VectoDE/VectolynCloud/.github/workflows/release.yml@refs/tags/v0.2.0-dev" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

gh attestation verify oci://ghcr.io/vectode/vectolyn-cloud:0.2.0-dev --repo VectoDE/VectolynCloud

# SBOM und BuildKit-Provenance, die mit dem Image gespeichert sind
docker buildx imagetools inspect ghcr.io/vectode/vectolyn-cloud:0.2.0-dev --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/vectode/vectolyn-cloud:0.2.0-dev --format '{{ json .Provenance }}'
```

Ob das Image das veröffentlichte Jar enthält: `docker run --rm --entrypoint sha256sum ghcr.io/vectode/vectolyn-cloud:0.2.0-dev /opt/vectolyn/VectolynCloud.jar` muss die Zeile aus `SHA256SUMS` ausgeben.

## Release-Banner

Jedes Release beginnt mit einem Banner: die Wortmarke VectolynCloud, der Tag (`v1.0.0-beta.2`), ein Badge für den Zusatz (`-dev` PRE-RELEASE, `-alpha` ALPHA, `-beta` BETA, `-rc` RC, `-lts` LTS, bei stabilen Versionen keins; siehe [Versionen und Tags](#versionen-und-tags)), die erste Zeile der Tag-Nachricht als Titel und das Datum des Tags. Der Build-Job von `release.yml` zeichnet es mit [`tools/release-banner`](../../tools/release-banner) (reines Java, ohne Abhängigkeiten) auf den vorgerenderten Hintergrund aus [`branding/png/`](../../branding/png), in Pixelify Sans Bold aus [`branding/fonts/`](../../branding/fonts). Beide Größen sind Release-Dateien: Sie stehen in `SHA256SUMS`, sind signiert und von der Provenance abgedeckt wie die anderen, und die Release Notes zeigen die 2x-Datei.

Was ins Banner kommt:

- **Titel:** die erste Zeile der Nachricht des annotierten Tags, also eine kurze Überschrift: `git tag -a v0.2.0-dev -m "Stage 2: cloud core" -m "Längere Notizen ..."`. Ab 49 Zeichen wird sie am Wortende mit `…` gekürzt, und weiter gekürzt, wenn sie nicht neben das Datum passt. Ein Tag ohne Nachricht, eine Nachricht, die nur aus einer Signatur besteht, und ein Lightweight-Tag ergeben ein Banner ohne Titel.
- **Datum:** das Datum des Taggers in UTC (bei einem Lightweight-Tag das Commit-Datum), angezeigt als `Oct 30, 2026`.
- **Strenge Prüfung:** Der Tag muss den [Versionsregeln](#versionen-und-tags) folgen, der Titel darf keine Steuer- oder Formatzeichen enthalten, und jedes Zeichen muss in Pixelify Sans vorhanden sein (Lateinisch, Lateinisch erweitert und ein Teil des Kyrillischen; keine Emoji). Stimmt etwas nicht, schlägt das Release fehl, bevor etwas veröffentlicht ist; dann den Tag löschen, korrigieren und neu pushen.

Vor dem Taggen lässt sich ein Banner lokal ansehen (die Ausgabe landet in `tools/release-banner/build/release-banner/`, außer `-PbannerOut` nennt ein anderes Verzeichnis):

```sh
./gradlew :tools:release-banner:releaseBanner -PbannerVersion=v1.0.0-beta.2 \
  -PbannerTitle="Stage 2: cloud core" -PbannerDate=2026-10-30
```

Genau das, was das Release für einen lokal vorhandenen Tag zeigen wird, erzeugt das Skript des Workflows: `tools/release-banner/banner-from-tag.sh v0.2.0-dev out/`.

## Was wo getestet wird

| Teil | In der CI getestet ([operations.yml](../../.github/workflows/operations.yml)) | Zeigt erst ein echtes Release oder eine echte Installation |
|---|---|---|
| Startskripte | Linux, macOS, Windows (PowerShell 5.1), alle Fälle oben | |
| Release-Archive | mit `package.sh` gebaut, zweimal gebaut und verglichen, entpackt und gestartet | den Upload ins Release |
| systemd-Unit | `systemd-analyze verify`, echter Start und Stopp unter Ubuntu | lang laufende Server und die verwaltete MariaDB unter der Härtung |
| Image | aus den Quellen und über den Release-Weg, amd64 und arm64, Start als UID 1000, root abgelehnt, Healthcheck, sauberes `docker stop` | Push nach GHCR, cosign-Signatur, Attestierungen, die `latest`-Regel |
| compose.yml | `docker compose config` | einen vollständigen Start mit MariaDB |
| Egg | Struktur, Variablen, Regeln gegen ihre Standardwerte, die Setup-Datei, die Wings schreiben würde, ShellCheck des Installers | Import in ein Panel, den Download des Installers, einen Lauf unter Wings |
| Release-Tag | Unit-Tests jeder Regel; `check-tag.sh` auf jeder erlaubten Form (Kanal und Vorab-Kennzeichen) und jeder abgelehnten Form (der Grund) | die Prüfung eines gepushten Tags, das Vorab-Kennzeichen und `latest` auf GitHub und GHCR |
| Release-Banner | Unit-Tests (Badge je Zusatz, Kürzen des Titels, Größen, bytegleiche Ausgabe); das Release-Skript auf lokalen Tags (jedes Badge, mit Titel, leere Nachricht, nur Signatur, Lightweight, Tags, die die Regeln verletzen), zweimal erzeugt und verglichen | den Abruf des Tags von GitHub und das Bild in den veröffentlichten Notes |
| Release-Workflow | actionlint | Signieren, Provenance und Veröffentlichen bei einem Tag |
