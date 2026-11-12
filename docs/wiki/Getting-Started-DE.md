# Erste Schritte

[English](Getting-Started)

Vom Download bis zum laufenden Netzwerk. Dies ist ein Pre-Release: v0.1.1-dev ist zum Ausprobieren gedacht, nicht für den Produktivbetrieb. Details je Plattform stehen unter [Betrieb](Operations-DE).

## Was du brauchst

- Java 21 oder neuer. Die Cloud selbst läuft darauf; die Server, die sie startet, bekommen die Java-Version, die ihre Minecraft-Version braucht, und die Cloud lädt eine passende Laufzeit herunter, falls keine installiert ist.
- Ein normales Benutzerkonto. Die Cloud startet nicht als root oder Administrator.
- Internetzugang beim ersten Start, damit die Cloud die Server-Software herunterladen kann.
- Arbeitsspeicher für die gewählten Server. Der Einrichtungsassistent addiert die Heaps und warnt, wenn sie den Rechner übersteigen.

## 1. Herunterladen und prüfen

Nimm das Archiv für dein System vom [neuesten Release](https://github.com/VectoDE/VectolynCloud/releases): `VectolynCloud-<version>-windows.zip` oder `VectolynCloud-<version>-linux-macos.tar.gz`. Prüfe es gegen `SHA256SUMS` und die Signatur, siehe [Download prüfen](Operations-DE#einen-download-prüfen).

## 2. Entpacken und starten

Entpacke das Archiv in einen leeren Ordner. Dieser Ordner ist das Cloud-Home: Konfiguration, Welten, Datenbank und Downloads landen alle darin.

| System | Start |
|---|---|
| Windows | `start.bat` in einer normalen Eingabeaufforderung oder PowerShell (nicht „Als Administrator ausführen“) |
| Linux, macOS | `./start.sh` |
| Docker | `docker run -it --init -v vectolyn:/data -p 25565:25565 -p 19132:19132/udp ghcr.io/vectode/vectolyn-cloud:<version>` |
| Pterodactyl | das Egg importieren, siehe [Betrieb](Operations-DE) |

## 3. Den Einrichtungsassistenten beantworten

Beim ersten Start fragt die Cloud in dieser Reihenfolge: Sprache, Minecraft-EULA, Netzwerkname, welche Server du willst (Proxy, Lobby, Survival, Creative, Mod-Server, End, Minigames, Legacy-Proxy), Ports, Speicher je Server, Anzahl der Dienste und die Datenbank (verwaltete MariaDB, Docker, eine vorhandene MariaDB/MySQL oder H2). Enter übernimmt den Standardwert in Klammern. Am Ende zeigt der Assistent eine Zusammenfassung und schreibt nichts, bis du `save` wählst. Ohne deine Zustimmung zur EULA startet kein Server.

Ohne Terminal (systemd, Docker ohne `-it`, Pterodactyl) liest der Assistent stattdessen eine Setup-Datei: `./start.sh --non-interactive --setup-file setup.yml`. Das Format steht in [docs/cloud/setup.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.de.md).

## 4. Laufen lassen

Die Cloud erzeugt die Secrets, lädt die Server-Software und Plugins herunter und prüft sie, rendert jeden gewollten Server aus seinem Template in einen eigenen Ordner unter `local/services/` und startet ihn. Der Start zeigt ein Banner und eine Zeile je Schritt. Nicht gewählte Server entstehen nicht, und weder Proxy noch Lobby verweisen auf sie.

Verbinde dich mit einem Java-Client zu `localhost:25565` oder mit einem Bedrock-Client zu `localhost`, Port `19132`. Ports aller Server: [Server-Netzwerk](Server-Network-DE).

In der Konsole listet `help` die Befehle, `service list` zeigt die Dienste und `shutdown` beendet alles. Nach der ersten Einrichtung änderst du die Konfiguration über Dateien und Konsolenbefehle, siehe [Konfiguration](Configuration-DE). Skripte können die [API](API-DE) nutzen.

## Wo was liegt

Sichere `config/`, `local/` und `secrets/`. Downloads, Java-Laufzeiten und Logs entstehen von selbst neu. Die vollständige Ordnerstruktur: [Betrieb](Operations-DE#wo-die-dateien-liegen).

## Eine Änderung aus dem Repository ausprobieren

So probierst du den aktuellen Stand des Codes oder eine eigene Änderung aus:

```sh
./gradlew :cloud:node:runLocal --console=plain
```

Das baut `VectolynCloud.jar` und startet sie mit `run/` im Wurzelverzeichnis des Repositorys als Cloud-Home. Der Einrichtungsassistent fragt im selben Terminal; alles bleibt in `run/`, das Git ignoriert. Beende mit `shutdown`, lösche `run/`, um neu anzufangen. Läuft deine Shell als Administrator oder root, hänge `-PallowRoot=true` an. `run/` ist nur zum Entwickeln da. Details: [docs/cloud/launcher.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/launcher.md#local-start-from-the-repository) (Englisch).

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
