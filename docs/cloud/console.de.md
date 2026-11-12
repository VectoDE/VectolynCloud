# VectolynCloud-Konsole: Befehlsreferenz

Modul `cloud/console`. Die ausführliche Modulseite mit Terminal-Auswahl, Erweiterung und Frage-API steht auf Englisch in [console.md](console.md). Diese Seite beschreibt die Bedienung.

Alle Texte der Konsole erscheinen in der Sprache aus `language` in `config/cloud.yml`. Ein `config set language de` wirkt sofort. Dateinamen in Meldungen und Befehlen (`groups/lobby.yml`) sind relativ zu `config/`.

Alle Log-Zeilen haben ein Format: `HH:mm:ss LEVEL Nachricht`, mit den festen Stufen `ERROR`, `WARN `, `INFO `, `DEBUG` und `TRACE`, unabhängig von der Systemsprache. Bis die Konsole läuft, schreibt ein Handler auf die Standardausgabe in der Codepage der Konsole, damit Umlaute auch unter Windows stimmen. `logs/cloud.log` bekommt dieselben Einträge in UTF-8 mit Datum, Millisekunden, Logger und Stacktraces; bei jedem Start, um Mitternacht und ab `logs.max-file-size` wird zu `cloud-<Datum>.log` rotiert und nach `logs.retention-days` Tagen gelöscht (`RotatingLogFile`, [config.md](config.md#cloudyml)). Banner und Schritt-Zeilen des Starts stehen in [Start output](node.md#start-output) (Englisch).

## Bedienung

**Im Terminal** (JLine): Prompt `vectolyn> `, Zeilenbearbeitung, Tab vervollständigt Befehle, Unterbefehle und Werte: Schlüssel aus `cloud.yml`, Dienst-IDs, Gruppen-, Task- und Node-Namen, IDs offener Beitritts-Tokens, Cluster-Mitglieder, Enum-Werte und feste Auswahlen (`on`/`off`, `--yes`). Die Vorschläge kommen bei jedem Tab aus dem aktuellen Stand; passt nichts, gibt es keinen Vorschlag. Farben gibt es nur, wenn das Terminal mindestens 8 Farben meldet und `NO_COLOR` nicht gesetzt ist. Log-Zeilen erscheinen über dem Prompt, die angefangene Eingabe bleibt stehen. Strg+C oder Strg+D am Prompt fragen erst nach („Zum Stoppen aller Dienste und der Cloud innerhalb von 3 Sekunden erneut Strg+C drücken oder shutdown eingeben.“). Erst ein zweites Strg+C oder Strg+D innerhalb von 3 Sekunden führt `shutdown` aus. Ein einzelner Tastendruck stoppt also nicht das ganze Netzwerk. Während ein Befehl läuft, wird Strg+C ignoriert.

Die History liegt in `<home>/state/console.history` (höchstens 500 Zeilen, nur für den Besitzer lesbar). Nicht gespeichert werden Antworten auf Fragen, Secrets, Zeilen von Befehlen, die als sensibel markiert sind, und Zeilen, die mit einem Leerzeichen beginnen.

**Ohne Terminal** (Docker ohne `-t`, Pterodactyl, systemd): Die Konsole liest stdin Zeile für Zeile, ohne Prompt, ohne Farben, ohne History. Endet stdin, hört die Konsole nur auf zu lesen; die Cloud läuft weiter und wird per Signal beendet (SIGTERM, `docker stop`). Strg+C kommt hier als Signal an. In beiden Fällen fährt die Cloud sauber herunter, jeder Dienst bekommt seinen Stopp-Befehl. Eine Frage bricht man hier mit `!cancel` ab.

Erzwingen lässt sich die Art mit der System-Property `vectolyn.console` oder der Umgebungsvariable `VECTOLYN_CONSOLE`: `auto`, `ffm`, `jni`, `dumb` oder `plain`.

## Eingabe

- Leerzeichen trennen Wörter. `'einfache Anführungszeichen'` nehmen alles wörtlich.
- In `"doppelten Anführungszeichen"` gelten nur `\"` und `\\` als Escape, sonst bleibt der Backslash stehen (`"C:\Vectolyn"`).
- Außerhalb von Anführungszeichen nimmt `\` das nächste Zeichen wörtlich (`a\ b` ist ein Wort).
- `""` ist ein leeres Wort und leert einen optionalen Text.
- Abgelehnt werden nicht geschlossene Anführungszeichen, ein Backslash am Zeilenende, Steuerzeichen und Zeilenumbrüche, mehr als 4096 Zeichen und mehr als 64 Wörter. Die Meldung nennt die Regel und die Position, nie die Eingabe.

## Befehle

### `help [Befehl] [Unterbefehl]`

Ohne Argument die Liste aller Befehle, mit Befehl der Aufruf, die Aliase, Unterbefehle und Argumente.

### `shutdown`, `exit`

Stoppt alle Dienste und die Cloud. Im Terminal tut zweimal Strg+C oder Strg+D innerhalb von 3 Sekunden dasselbe.

### `service`

Kommt vom Node: listet, startet, stoppt und beobachtet die Server.

| Befehl | Wirkung |
|---|---|
| `service list` | alle Dienste mit Zustand, Port, PID und Spielerzahl |
| `service info <ID>` | ein Dienst im Detail, mit seinem letzten Statusbericht |
| `service start <Task>` | startet einen weiteren Dienst des Tasks |
| `service stop <ID>` | stoppt den Dienst und wartet, bis er beendet ist |
| `service restart <ID>` | stoppt den Dienst und startet ihn neu |
| `service command <ID> <Zeile>` (`cmd`) | gibt eine Zeile in die Konsole des Servers ein |
| `service screen <ID>` (`logs`) | zeigt die Ausgabe des Servers hier an, noch einmal aufgerufen blendet es sie aus |

### `clear`, `cls`

Leert den Bildschirm, wenn das Terminal ANSI kann.

### `config`

| Unterbefehl | Wirkung |
|---|---|
| `config get <Schlüssel>` | Zeigt einen Wert aus `cloud.yml`. |
| `config set <Schlüssel> <Wert>` | Ändert ihn. Geprüft wird wie bei einer Änderung von Hand: Typen, Muster, Bereiche und die Regeln über die ganze Konfiguration (Ports, EULA vor `setup-complete`, TLS für eine öffentliche API). Bei einem Fehler wird nichts geschrieben. |
| `config list` | Alle Schlüssel mit Wert. |
| `config reload` | Liest alle Konfigurationsdateien neu. Eine fehlerhafte Datei lässt die aktuelle Konfiguration aktiv und zeigt die Fehler. |
| `config set <Schlüssel> <Wert> --dry-run` | Prüft die Änderung wie `config set` und zeigt den Zeilen-Diff von `cloud.yml`, schreibt aber nichts ([Vorschau](config.md#preview-of-a-change)). |
| `config export <datei.zip>` | Schreibt die ganze Konfiguration (`cloud.yml`, `nodes.yml`, Gruppen, Tasks, `monitor.yml`, `privacy.yml`, `backup.yml`) in eine neue Zip-Datei, nie Secrets. Ein relativer Pfad beginnt im Cloud-Home; eine vorhandene Datei wird nie überschrieben ([Import und Export](config.md#import-and-export)). |
| `config import <datei.zip> [--dry-run]` | Prüft jede Datei des Archivs, zeigt den Diff jeder Datei, die sich ändert, und übernimmt dann alles; mit `--dry-run` endet der Befehl nach dem Diff. Auf einem Cluster-Mitglied wird ein Archiv abgelehnt, das Dateien des Heads ändert. |
| `config warnings` | Listet, was zwar lädt, aber riskant ist, etwa ein JVM-Flag in einer Gruppendatei außerhalb der Erlaubnisliste. Dieselbe Liste wie `GET /api/v1/config/warnings`. |

Schlüssel: `network-name`, `language`, `eula-accepted`, `setup-complete`, `node-id`, `fallback-group`, `servers.<proxy|lobby|survival|creative|mod|end|database|minigame|legacy-server>`, `proxy.bind`, `proxy.port`, `proxy.bedrock-port`, `legacy-proxy.enabled`, `legacy-proxy.port`, `database.mode`, `database.host`, `database.port`, `database.name`, `database.user`, `database.docker-image`, `api.enabled`, `api.bind`, `api.port`, `api.grpc-port`, `api.tls.enabled`, `api.tls.keystore`, `api.cors-origins`, `api.graphql-introspection`, `branding.powered-by`, `logs.retention-days`, `logs.max-file-size`.

Werte wie in der Datei: nur `true` oder `false`, ganze Zahlen, Enum-Werte klein geschrieben. `api.cors-origins` nimmt eine durch Komma getrennte Liste von Origins, `""` schaltet CORS aus. `fallback-group` muss eine bestehende Gruppe nennen, `""` setzt sie auf den Standard `lobby` zurück. Passwörter stehen nicht in `cloud.yml`, sondern in `secrets/vectolyn.env`.

### `setup`

Startet den Einrichtungsassistenten erneut. Die aktuellen Werte sind die Vorschläge, geschrieben wird nur, was sich ändert. Siehe [Einrichtung](setup.de.md).

### `group`

| Unterbefehl | Wirkung |
|---|---|
| `group list` | Alle Gruppen mit Template, Software, Version und ob ihre Serverart gewünscht ist. |
| `group info <Name>` | Alle Felder einer Gruppe. |
| `group create <Name> <Template> <Software> <Minecraft-Version>` | Neue `groups/<Name>.yml` mit `java: auto`, Heap 512M bis 1G, Kampfsystem `legacy` bei `pandaspigot`, sonst `modern`. Scheitert, wenn es die Gruppe schon gibt. |
| `group set <Name> <Feld> <Wert>` | Felder: `template`, `software`, `minecraft-version`, `java`, `heap.min`, `heap.max`, `combat`, `wanted` (`on` oder `off`, der eigene Schalter der Gruppe, etwa für ein Minispiel von mehreren; `on` entfernt den Eintrag, weil es der Standard ist), `drain-timeout` (5s bis 10m, `default` entfernt den Wert, dann gelten 30s), zum Beispiel `group set lobby drain-timeout 45s`. |
| `group delete <Name> [--yes]`, `group remove <Name> [--yes]` | Löscht die Datei nach Rückfrage. Scheitert, solange ein Task die Gruppe nutzt. |
| `group wanted <Name> <on\|off>` | Schaltet die Serverart der Gruppe in `cloud.yml` (`servers:`) ein oder aus. |

### `task`

| Unterbefehl | Wirkung |
|---|---|
| `task list` | Alle Tasks mit Gruppe, Modus (`static`, `dynamic` oder `static N + dynamic`) und Dienst-Bereich. |
| `task info <Name>` | Alle Felder eines Tasks. |
| `task create <Name> <Gruppe> [static\|dynamic]` | Neue `tasks/<Name>.yml` auf beliebigem Node: `static` (Standard) mit einem statischen Dienst (1/1/1), `dynamic` mit einem dynamischen (0/1/1). Die Gruppe muss existieren. |
| `task set <Name> <Feld> <Wert>` | Felder: `group`, `static-services`, `min-services`, `max-services`, `players-per-service`, `scale-up-at`, `scale-down-below`, `scale-down-after`, `cooldown`, `nodes` (Liste mit Kommas, `""` für beliebigen Node) und `mode` als Abkürzung: `mode static` setzt statisch, min und max auf `min-services` (mindestens 1, wie die Migration eines Tasks der Version 1), `mode dynamic` setzt `static-services` auf 0 und lässt min und max stehen. Geprüft wird wie in der Datei (`static ≤ min ≤ max`, `scale-down-below` < `scale-up-at`). Der [Autoscaler](scaling.de.md) übernimmt eine Änderung innerhalb einer Runde. |
| `task delete <Name> [--yes]`, `task remove <Name> [--yes]` | Löscht die Datei nach Rückfrage. |
| `task wanted <Name> <on\|off>` | Schaltet die Serverart der Gruppe des Tasks ein oder aus. |

Vor dem Löschen prüft die Konsole, ob es nach denselben Regeln wie `ConfigService.apply` klappen würde, und fragt dann `groups/<Name>.yml wirklich löschen? [j/N]`. Enter, `n` oder Strg+C lassen die Datei stehen. Ohne Terminal gilt die nächste Zeile von stdin als Antwort. In Skripten entfällt die Frage mit `--yes` oder `-y` hinter dem Namen.

Es gibt zwei Schalter, eine Gruppe läuft nur, wenn beide an sind. `group set <Name> wanted off` schaltet eine einzelne Gruppe ab (`wanted: false` in ihrer Datei); `group info` zeigt `wanted` (Schalter der Gruppe) und `server kind` (Schalter der Serverart). `group wanted` und `task wanted` schalten die Serverart: Eine Gruppe gehört zu der Serverart, deren Template-Ordner sie nutzt (`Lobby-Server` ist `lobby`). Der Schalter wirkt also auf alle Gruppen mit diesem Template. Für eine Gruppe mit anderem Template gibt es keinen Schalter, der Befehl meldet das.

Den Katalog einer Gruppe (Plugins und Mods) ändert man vorerst in der Datei. Befehle für Dienste (`start`, `stop`, `screen` usw.) bringt cloud-node mit.

### `node`

| Unterbefehl | Wirkung |
|---|---|
| `node list` | Alle Nodes aus `nodes.yml` mit Host, Kanal-Port, `max-memory` und dem Hinweis, welcher Node dieser ist. |
| `node info <ID>` | Alle Felder eines Nodes samt Speicherreserve. Ein nicht gesetztes Feld zeigt seinen Default und `(default)`. |
| `node status` | Der laufende Node: Version, Java, Betriebssystem, Laufzeit, Heap und wie viele Dienste laufen. Kommt von cloud-node, dieselben Werte wie `GET /api/v1/node`. |
| `node create <ID> <Host> <Port> <Dienst-Ports>` | Trägt einen Node in `nodes.yml` ein, zum Beispiel `node create node-2 10.0.0.2 7010 30000-30999`. Die Speichereinstellungen starten mit ihren Standards. Scheitert, wenn es die ID schon gibt. |
| `node set <ID> <Feld> <Wert>` | Felder: `host`, `port` (Cloud-Kanal), `service-ports` (`erster-letzter`), `max-memory` (mindestens 512M, `auto` entfernt den Wert, dann rechnet der Node selbst), `memory-reserve.system`, `memory-reserve.node-overhead`, `memory-reserve.database` (je 1M bis 64G, `default` entfernt den Wert, dann gilt der Default: 1G, 256M, 512M). Zum Beispiel `node set node-1 memory-reserve.system 2G`. |
| `node delete <ID> [--yes]`, `node remove <ID> [--yes]` | Entfernt den Node nach Rückfrage aus `nodes.yml`, wie `group delete`. Scheitert, solange `cloud.yml` (`node-id`) oder ein Task (`nodes`) ihn nennt. |

Jede Änderung wird wie eine Änderung von Hand an `nodes.yml` geprüft (Ports 1 bis 65535, `first` nicht über `last`, Kanal-Port außerhalb der Dienst-Ports, gültiger Host). Bei einem Fehler wird nichts geschrieben. Siehe [config.md](config.md#nodesyml).

### `api`

Kommt aus `cloud/api` ([api.de.md](api.de.md)). Der ganze Befehl gilt als sensibel, keine seiner Zeilen landet in der History.

| Zeile | Wirkung |
|---|---|
| `api token create <Name> <Scopes>` | Erstellt ein Token mit den durch Komma getrennten Scopes (`services:read,config:read`) und zeigt es einmal an. Gespeichert wird nur der SHA-256-Wert. |
| `api token list` | ID, Name, Erstellungszeit und Scopes aller Tokens, nie das Token selbst. |
| `api token revoke <ID\|Name>` | Widerruft sofort; offene Event-Streams des Tokens werden geschlossen. Aliase: `delete`, `remove`. |
| `api token setup [Name]` | Erstellt einen neuen einmaligen Einrichtungs-Token für den ersten Administrator (Standard `admin`), 24 Stunden gültig, und zeigt ihn einmal an; er ersetzt einen früheren. Siehe [Einrichtung](setup.de.md#erster-administrator). |
| `api scopes` | Listet die Scopes auf. |

### `cluster`

Kommt aus `cloud/cluster`, die Unterbefehle stehen in [cluster.md](cluster.md). Tab ergänzt nach `cluster token revoke` die IDs offener Beitritts-Tokens und nach `cluster remove` die Mitglieder, beides nur auf dem Head.

## Konsole und REST-API

Jede lesende und schreibende Funktion der [REST-API](api.de.md) gibt es auch als Konsolenbefehl. Die Zuordnung steht in [console.md](console.md#console-and-rest-api); `ConsoleParityTest` in cloud-node schlägt fehl, sobald ein Endpunkt dort fehlt oder sein Befehl nicht existiert. Kurz: `service ...` für Dienste, `group`/`task`/`node` für die Dateien, `config ...` für `cloud.yml`, Server-Schalter, Neuladen und Warnungen, `node status` für `GET /node`, `backup list|create` für die Backups.

Noch in den Dateien geändert werden der Plugin- und Mod-Katalog einer Gruppe, Templates, `monitor.yml` und `backup.yml`, weil es dafür in der Cloud noch gar keine Schreib-Operation gibt. Funktionen, die die Cloud noch nicht hat (Updates, Wartungsmodus, Platzierungsregeln, Templates bearbeiten), bekommen ihre Konsolenbefehle zusammen mit der Funktion in eigenen Issues.

## Fragen von Assistenten

Der Einrichtungsassistent und andere Assistenten stellen Fragen über dieselbe Konsole:

- Enter übernimmt den Standardwert in eckigen Klammern.
- Ja/Nein versteht `j`, `ja`, `y`, `yes`, `n`, `nein`, `no`.
- Bei einer Auswahl gilt die Nummer oder der Name in Klammern.
- Secrets werden maskiert eingegeben, nie angezeigt und nie in der History gespeichert. Maskieren kann nur ein natives Terminal (`ffm`, `jni`). Im `dumb`-Terminal von JLine und ohne Terminal bleibt die Eingabe sichtbar; die Frage warnt vorher und empfiehlt, mit `!cancel` abzubrechen und den Wert in `secrets/vectolyn.env` einzutragen. „Enter behält den aktuellen Wert“ lässt ein bestehendes Secret unverändert.
- Eine ungültige Antwort zeigt, was erwartet wird, und fragt erneut, höchstens zehnmal hintereinander.
- Strg+C, Strg+D, das Ende von stdin oder `!cancel` brechen ab.
