# VectoraCloud-Konsole: Befehlsreferenz

Modul `VectoraCloud/cloud-console`. Die ausführliche Modulseite mit Terminal-Auswahl, Erweiterung und Frage-API steht auf Englisch in [console.md](console.md). Diese Seite beschreibt die Bedienung.

Alle Texte der Konsole erscheinen in der Sprache aus `language` in `cloud.yml`. Ein `config set language de` wirkt sofort.

## Bedienung

**Im Terminal** (JLine): Prompt `vectora> `, Zeilenbearbeitung, Tab vervollständigt Befehle, Unterbefehle und Werte. Farben gibt es nur, wenn das Terminal mindestens 8 Farben meldet und `NO_COLOR` nicht gesetzt ist. Log-Zeilen erscheinen über dem Prompt, die angefangene Eingabe bleibt stehen. Strg+C oder Strg+D am Prompt fragen erst nach („Zum Stoppen aller Dienste und der Cloud innerhalb von 3 Sekunden erneut Strg+C drücken oder shutdown eingeben.“). Erst ein zweites Strg+C oder Strg+D innerhalb von 3 Sekunden führt `shutdown` aus. Ein einzelner Tastendruck stoppt also nicht das ganze Netzwerk. Während ein Befehl läuft, wird Strg+C ignoriert.

Die History liegt in `<home>/state/console.history` (höchstens 500 Zeilen, nur für den Besitzer lesbar). Nicht gespeichert werden Antworten auf Fragen, Secrets, Zeilen von Befehlen, die als sensibel markiert sind, und Zeilen, die mit einem Leerzeichen beginnen.

**Ohne Terminal** (Docker ohne `-t`, Pterodactyl, systemd): Die Konsole liest stdin Zeile für Zeile, ohne Prompt, ohne Farben, ohne History. Endet stdin, hört die Konsole nur auf zu lesen; die Cloud läuft weiter und wird per Signal beendet (SIGTERM, `docker stop`). Strg+C kommt hier als Signal an. In beiden Fällen fährt die Cloud sauber herunter, jeder Dienst bekommt seinen Stopp-Befehl. Eine Frage bricht man hier mit `!cancel` ab.

Erzwingen lässt sich die Art mit der System-Property `vectora.console` oder der Umgebungsvariable `VECTORA_CONSOLE`: `auto`, `ffm`, `jni`, `dumb` oder `plain`.

## Eingabe

- Leerzeichen trennen Wörter. `'einfache Anführungszeichen'` nehmen alles wörtlich.
- In `"doppelten Anführungszeichen"` gelten nur `\"` und `\\` als Escape, sonst bleibt der Backslash stehen (`"C:\Vectora"`).
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

Schlüssel: `network-name`, `language`, `eula-accepted`, `setup-complete`, `node-id`, `servers.<proxy|lobby|survival|creative|mod|end|database|minigame|legacy-server>`, `proxy.bind`, `proxy.port`, `proxy.bedrock-port`, `legacy-proxy.enabled`, `legacy-proxy.port`, `database.mode`, `database.host`, `database.port`, `database.name`, `database.user`, `database.docker-image`, `api.enabled`, `api.bind`, `api.port`, `api.tls.enabled`, `api.tls.keystore`, `api.cors-origins`.

Werte wie in der Datei: nur `true` oder `false`, ganze Zahlen, Enum-Werte klein geschrieben. `api.cors-origins` nimmt eine durch Komma getrennte Liste von Origins, `""` schaltet CORS aus. Passwörter stehen nicht in `cloud.yml`, sondern in `secrets/vectora.env`.

### `setup`

Startet den Einrichtungsassistenten erneut. Die aktuellen Werte sind die Vorschläge, geschrieben wird nur, was sich ändert. Siehe [Einrichtung](setup.de.md).

### `group`

| Unterbefehl | Wirkung |
|---|---|
| `group list` | Alle Gruppen mit Template, Software, Version und ob ihre Serverart gewünscht ist. |
| `group info <Name>` | Alle Felder einer Gruppe. |
| `group create <Name> <Template> <Software> <Minecraft-Version>` | Neue `groups/<Name>.yml` mit `java: auto`, Heap 512M bis 1G, Kampfsystem `legacy` bei `pandaspigot`, sonst `modern`. Scheitert, wenn es die Gruppe schon gibt. |
| `group set <Name> <Feld> <Wert>` | Felder: `template`, `software`, `minecraft-version`, `java`, `heap.min`, `heap.max`, `combat`. |
| `group delete <Name> [--yes]`, `group remove <Name> [--yes]` | Löscht die Datei nach Rückfrage. Scheitert, solange ein Task die Gruppe nutzt. |
| `group wanted <Name> <on\|off>` | Schaltet die Serverart der Gruppe in `cloud.yml` (`servers:`) ein oder aus. |

### `task`

| Unterbefehl | Wirkung |
|---|---|
| `task list` | Alle Tasks mit Gruppe, Modus und Dienst-Bereich. |
| `task info <Name>` | Alle Felder eines Tasks. |
| `task create <Name> <Gruppe> [static\|dynamic]` | Neue `tasks/<Name>.yml` mit einem Dienst auf beliebigem Node. Die Gruppe muss existieren. |
| `task set <Name> <Feld> <Wert>` | Felder: `group`, `mode`, `min-services`, `max-services`, `players-per-service`, `scale-up-at`, `scale-down-after`, `cooldown`, `nodes` (Liste mit Kommas, `""` für beliebigen Node). Der [Autoscaler](scaling.de.md) übernimmt eine Änderung innerhalb einer Runde. |
| `task delete <Name> [--yes]`, `task remove <Name> [--yes]` | Löscht die Datei nach Rückfrage. |
| `task wanted <Name> <on\|off>` | Schaltet die Serverart der Gruppe des Tasks ein oder aus. |

Vor dem Löschen prüft die Konsole, ob es nach denselben Regeln wie `ConfigService.apply` klappen würde, und fragt dann `groups/<Name>.yml wirklich löschen? [j/N]`. Enter, `n` oder Strg+C lassen die Datei stehen. Ohne Terminal gilt die nächste Zeile von stdin als Antwort. In Skripten entfällt die Frage mit `--yes` oder `-y` hinter dem Namen.

`wanted` gilt je Serverart, nicht je Gruppe. Eine Gruppe gehört zu der Serverart, deren Template-Ordner sie nutzt (`Lobby-Server` ist `lobby`). Der Schalter wirkt also auf alle Gruppen mit diesem Template. Für eine Gruppe mit anderem Template gibt es keinen Schalter, der Befehl meldet das.

Den Katalog einer Gruppe (Plugins und Mods) ändert man vorerst in der Datei. Befehle für Dienste (`start`, `stop`, `screen` usw.) bringt cloud-node mit.

### `api`

Kommt aus `cloud-api` ([api.de.md](api.de.md)). Der ganze Befehl gilt als sensibel, keine seiner Zeilen landet in der History.

| Zeile | Wirkung |
|---|---|
| `api token create <Name> <Scopes>` | Erstellt ein Token mit den durch Komma getrennten Scopes (`services:read,config:read`) und zeigt es einmal an. Gespeichert wird nur der SHA-256-Wert. |
| `api token list` | ID, Name, Erstellungszeit und Scopes aller Tokens, nie das Token selbst. |
| `api token revoke <ID\|Name>` | Widerruft sofort; offene Event-Streams des Tokens werden geschlossen. Aliase: `delete`, `remove`. |
| `api scopes` | Listet die Scopes auf. |

## Fragen von Assistenten

Der Einrichtungsassistent und andere Assistenten stellen Fragen über dieselbe Konsole:

- Enter übernimmt den Standardwert in eckigen Klammern.
- Ja/Nein versteht `j`, `ja`, `y`, `yes`, `n`, `nein`, `no`.
- Bei einer Auswahl gilt die Nummer oder der Name in Klammern.
- Secrets werden maskiert eingegeben, nie angezeigt und nie in der History gespeichert. Maskieren kann nur ein natives Terminal (`ffm`, `jni`). Im `dumb`-Terminal von JLine und ohne Terminal bleibt die Eingabe sichtbar; die Frage warnt vorher und empfiehlt, mit `!cancel` abzubrechen und den Wert in `secrets/vectora.env` einzutragen. „Enter behält den aktuellen Wert“ lässt ein bestehendes Secret unverändert.
- Eine ungültige Antwort zeigt, was erwartet wird, und fragt erneut, höchstens zehnmal hintereinander.
- Strg+C, Strg+D, das Ende von stdin oder `!cancel` brechen ab.
