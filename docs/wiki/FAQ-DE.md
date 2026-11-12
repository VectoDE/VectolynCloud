# FAQ

[English](FAQ)

**Gibt es einen offiziellen Vectolyn-Server?**
Nein, und es wird auch keinen geben. Vectolyn ist ein Baukasten: Wir veröffentlichen Code und Releases, jeder Betreiber betreibt sein eigenes Netzwerk. Das Projekt steht bei Etappe 2 von 8 (siehe [Roadmap](Roadmap-DE) und [Funktionsstand](Feature-Status-DE)); Releases kündigen wir unter [Discussions → Announcements](https://github.com/VectoDE/VectolynCloud/discussions/categories/announcements) an.

**Welche Minecraft-Versionen können spielen?**
Ziel sind Java 1.8 bis 26.3 und Bedrock. Was heute schon geht, steht unter [Crossplay](Crossplay-DE).

**Bekommen Bedrock-Spieler das volle Spiel?**
Das ist eine Projektregel: Kein Spielmodus erscheint, den Bedrock-Spieler nicht betreten können. Menüs sind Inventar-Menüs, die auf beiden Editionen funktionieren.

**Muss ich alle sieben Server betreiben?**
Nein. Im Einrichtungsassistenten oder in der Konfiguration wählst du die Server und Minigame-Gruppen, die du willst; nur die legt die Cloud an.

**Wie wird VectolynCloud konfiguriert?**
Ein Assistent in der Konsole beim ersten Start, danach Konfigurationsdateien und Konsolenbefehle (Etappe 2). Das Web-Dashboard soll das ebenfalls können (Etappe 8).

**Welche Java-Version brauche ich?**
Für den Betrieb der Server Java 25, weil Paper 26.3 es verlangt. Zum Bauen startet Gradle ab Java 17 und kompiliert mit einer Java-25-Toolchain, die es bei Bedarf herunterlädt. Der Bytecode der Plugins zielt auf Java 21, und alles, was Java 25 braucht, behält einen Java-21-Weg.

**Warum nicht einfach LuckPerms, WorldEdit, TAB und Co.?**
Vectolyn soll ein Plugin pro Plattform haben, das auf jedem Server gleich funktioniert und Bedrock kennt. Der Plan ersetzt LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal und Chunky durch eigene Module. Bis es die gibt, nutzen die aktuellen Server noch einige davon.

**Darf ich den Code für mein eigenes Netzwerk nutzen?**
Ja, genau dafür ist er da, im Rahmen der GPL-3.0. Gibst du eine geänderte Version weiter, musst du den Quellcode deiner Änderungen unter derselben Lizenz veröffentlichen.

**Wird es Pay-to-Win geben?**
Nein. Der geplante Store verkauft nur Kosmetik und hält sich an die Minecraft-EULA. Wir entwickeln ihn gegen den Stripe-Testmodus. Jeder Betreiber bindet sein eigenes Stripe-Konto an und ist selbst dafür verantwortlich, echte Zahlungen rechtlich sauber abzuwickeln.

## Probleme beim Start

**Die Cloud bricht sofort mit Exit-Code 2 ab.**
Sie wurde als root oder Administrator gestartet, was sie ablehnt. Starte sie aus einem normalen Benutzerkonto (unter Windows in einer normalen Konsole, nicht „Als Administrator ausführen“). Die Meldung erklärt, wie das auf deinem System geht. `--allow-root` oder `VECTOLYN_ALLOW_ROOT=true` erlauben den Start trotzdem; nutze das nur, wenn es keinen anderen Weg gibt.

**„VectolynCloud needs Java 21 or newer.“**
Die Java-Version in deinem `PATH` ist älter. Installiere Java 21 oder neuer (zum Beispiel Temurin von <https://adoptium.net/temurin/releases/>) oder setze `JAVA_HOME` darauf. Die Meldung nennt die gefundene Version und den Pfad.

**Kein Server startet und das Log sagt, die Einrichtung sei nicht abgeschlossen.**
Solange `setup-complete: true` nicht in der `config/cloud.yml` steht, startet die Cloud keinen Server. Beantworte den Einrichtungsassistenten oder starte ihn dort, wo es kein Terminal gibt, mit `--non-interactive --setup-file setup.yml`. Auch die Minecraft-EULA muss akzeptiert sein.

**PowerShell weigert sich, `start.ps1` auszuführen.**
Windows blockiert aus dem Internet geladene Skripte. Führe einmal `Unblock-File .\start.ps1` aus oder nutze `start.bat`, das beides nicht braucht.

**Unter systemd oder in Docker habe ich keine Konsole.**
Ohne Terminal hat die Cloud keine Konsole und läuft weiter. Nutze die [API](API-DE) für Befehle oder betreibe die Cloud in `tmux` oder `screen` als eigener Benutzer, wenn du die Konsole brauchst.

**Auf macOS (oder einem ARM-Server) ist die Datenbank H2, nicht MariaDB.**
MariaDB veröffentlicht für diese Systeme keinen Server-Build, deshalb fällt der Modus `managed` auf die eingebettete H2-Datenbank zurück und schreibt das ins Log. Für eine gemeinsame Datenbank nutze `external` oder Docker.

**Auf Pterodactyl scheitern mehrere Server mit „unable to create native thread“.**
Wings begrenzt jeden Container standardmäßig auf 512 Prozesse und Threads. Erhöhe `docker.container_pid_limit` in `/etc/pterodactyl/config.yml`, zum Beispiel auf 4096. Details unter [Betrieb](Operations-DE).

**Wo liegen die Logs?**
`logs/cloud.log` im Cloud-Home, und `logs/services/<id>.log` für jeden Server. Ältere Läufe und Tage bleiben als `cloud-<Datum>.log` und `<id>-<Datum>.log` 14 Tage erhalten; `logs.retention-days` in `cloud.yml` ändert das.

**Wo melde ich eine Sicherheitslücke?**
Privat, siehe [SECURITY.md](https://github.com/VectoDE/VectolynCloud/blob/main/SECURITY.md).

Weitere Fragen: [Q&A in den Discussions](https://github.com/VectoDE/VectolynCloud/discussions/categories/q-a).
