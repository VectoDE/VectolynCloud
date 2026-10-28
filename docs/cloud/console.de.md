# VectoraCloud-Konsole: Befehlsreferenz

Modul `VectoraCloud/cloud-console`. Die ausführliche Modulseite mit Terminal-Auswahl, Erweiterung und Frage-API steht auf Englisch in [console.md](console.md). Diese Seite beschreibt die Bedienung.

Alle Texte der Konsole erscheinen in der Sprache aus `language` in `cloud.yml`. Ein `config set language de` wirkt sofort.

## Bedienung

**Im Terminal** (JLine): Prompt `vectora> `, Zeilenbearbeitung, Tab vervollständigt Befehle, Unterbefehle und Werte. Farben gibt es nur, wenn das Terminal mindestens 8 Farben meldet und `NO_COLOR` nicht gesetzt ist. Log-Zeilen erscheinen über dem Prompt, die angefangene Eingabe bleibt stehen. Strg+C und Strg+D führen `shutdown` aus.

Die History liegt in `<home>/state/console.history` (höchstens 500 Zeilen, nur für den Besitzer lesbar). Nicht gespeichert werden Antworten auf Fragen, Secrets, Zeilen von Befehlen, die als sensibel markiert sind, und Zeilen, die mit einem Leerzeichen beginnen.

**Ohne Terminal** (Docker ohne `-t`, Pterodactyl, systemd): Die Konsole liest stdin Zeile für Zeile, ohne Prompt, ohne Farben, ohne History. Endet stdin, hört die Konsole nur auf zu lesen; die Cloud läuft weiter und wird per Signal beendet (SIGTERM, `docker stop`). Eine Frage bricht man hier mit `!cancel` ab.

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

Stoppt alle Dienste und die Cloud.

### `clear`, `cls`

Leert den Bildschirm, wenn das Terminal ANSI kann.

### `config`

| Unterbefehl | Wirkung |
|---|---|
| `config get <Schlüssel>` | Zeigt einen Wert aus `cloud.yml`. |
| `config set <Schlüssel> <Wert>` | Ändert ihn. Geprüft wird wie bei einer Änderung von Hand: Typen, Muster, Bereiche und die Regeln über die ganze Konfiguration (Ports, EULA vor `setup-complete`, TLS für eine öffentliche API). Bei einem Fehler wird nichts geschrieben. |
| `config list` | Alle Schlüssel mit Wert. |
| `config reload` | Liest alle Konfigurationsdateien neu. Eine fehlerhafte Datei lässt die aktuelle Konfiguration aktiv und zeigt die Fehler. |

Schlüssel: `network-name`, `language`, `eula-accepted`, `setup-complete`, `node-id`, `servers.<proxy|lobby|survival|creative|mod|end|database|minigame>`, `proxy.bind`, `proxy.port`, `proxy.bedrock-port`, `legacy-proxy.enabled`, `legacy-proxy.port`, `database.mode`, `database.host`, `database.port`, `database.name`, `database.user`, `database.docker-image`, `api.enabled`, `api.bind`, `api.port`, `api.tls.enabled`, `api.tls.keystore`.

Werte wie in der Datei: nur `true` oder `false`, ganze Zahlen, Enum-Werte klein geschrieben. Passwörter stehen nicht in `cloud.yml`, sondern in `secrets/vectora.env`.

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
| `task set <Name> <Feld> <Wert>` | Felder: `group`, `mode`, `min-services`, `max-services`, `players-per-service`, `scale-up-at`, `scale-down-after`, `cooldown`, `nodes` (Liste mit Kommas, `""` für beliebigen Node). |
| `task delete <Name> [--yes]`, `task remove <Name> [--yes]` | Löscht die Datei nach Rückfrage. |
| `task wanted <Name> <on\|off>` | Schaltet die Serverart der Gruppe des Tasks ein oder aus. |

Vor dem Löschen prüft die Konsole, ob es nach denselben Regeln wie `ConfigService.apply` klappen würde, und fragt dann `groups/<Name>.yml wirklich löschen? [j/N]`. Enter, `n` oder Strg+C lassen die Datei stehen. Ohne Terminal gilt die nächste Zeile von stdin als Antwort. In Skripten entfällt die Frage mit `--yes` oder `-y` hinter dem Namen.

`wanted` gilt je Serverart, nicht je Gruppe. Eine Gruppe gehört zu der Serverart, deren Template-Ordner sie nutzt (`Lobby-Server` ist `lobby`). Der Schalter wirkt also auf alle Gruppen mit diesem Template. Für eine Gruppe mit anderem Template gibt es keinen Schalter, der Befehl meldet das.

Den Katalog einer Gruppe (Plugins und Mods) ändert man vorerst in der Datei. Befehle für Dienste (`start`, `stop`, `screen` usw.) bringt cloud-node mit.

## Fragen von Assistenten

Der Einrichtungsassistent und andere Assistenten stellen Fragen über dieselbe Konsole:

- Enter übernimmt den Standardwert in eckigen Klammern.
- Ja/Nein versteht `j`, `ja`, `y`, `yes`, `n`, `nein`, `no`.
- Bei einer Auswahl gilt die Nummer oder der Name in Klammern.
- Secrets werden maskiert eingegeben, nie angezeigt und nie in der History gespeichert. „Enter behält den aktuellen Wert“ lässt ein bestehendes Secret unverändert.
- Eine ungültige Antwort zeigt, was erwartet wird, und fragt erneut, höchstens zehnmal hintereinander.
- Strg+C, Strg+D, das Ende von stdin oder `!cancel` brechen ab.
