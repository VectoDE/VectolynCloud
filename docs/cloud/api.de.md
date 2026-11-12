# VectolynCloud-REST-API

Modul `cloud/api`. Eine JSON-API über HTTP/1.1 und ein WebSocket für Live-Events, gedacht für das Dashboard (Etappe 8) und für Skripte. Die maschinenlesbare Beschreibung steht in [`api.openapi.yaml`](api.openapi.yaml) und ist unter <https://vectode.github.io/VectolynCloud/api/openapi.yaml> veröffentlicht. `OpenApiContractTest` vergleicht sie in jedem Build mit der Routing-Tabelle des Servers: Eine Route ohne Eintrag, ein Eintrag ohne Route oder ein falscher Scope lässt den Build scheitern. Die ausführliche englische Seite ist [api.md](api.md).

Die API ist standardmäßig aus. Sie startet mit dem Node, wenn in `config/cloud.yml` `api.enabled: true` steht:

```yaml
api:
  enabled: true
  bind: 127.0.0.1          # Standard ist Loopback; jede andere Adresse braucht TLS
  port: 8085
  tls:
    enabled: false
    # keystore: secrets/api.p12
  # cors-origins: [https://dashboard.example.org]   standardmäßig aus
```

Änderungen unter `api:` gelten ab dem nächsten Start des Nodes. Der laufende Server behält seinen Socket.

## Tokens

Jede Anfrage braucht ein Token. Ohne Token, mit einem falschen, widerrufenen oder abgelaufenen kommt `401` mit `WWW-Authenticate: Bearer`. Einen Endpunkt ohne Anmeldung gibt es nicht, ein Standardpasswort auch nicht. Das erste Token kommt von der Konsole oder aus dem einmaligen [Einrichtungs-Token](#erster-administrator).

Tokens verwaltet man an der Konsole des Nodes oder mit einem `admin`-Token über `/api/v1/admin/tokens` (`GET` listet, `POST` erstellt, `DELETE /admin/tokens/{id}` widerruft). Der ganze Befehl `api` gilt als sensibel, keine seiner Zeilen landet in der History.

```text
vectolyn> api token create dashboard services:read,config:read,node:read
vectolyn> api token create ci services:read 90d    # gilt 90 Tage
vectolyn> api token list
vectolyn> api token revoke dashboard          # oder die ID; Aliase: delete, remove
vectolyn> api token setup owner               # neuer einmaliger Einrichtungs-Token für den ersten Administrator
vectolyn> api scopes
```

- Format: `vct_` und 43 Zeichen aus `[A-Za-z0-9_-]` (32 Zufallsbytes aus `SecureRandom`, Base64url).
- `create` zeigt das Token genau einmal an. Gespeichert wird nur der SHA-256-Wert, in `<home>/secrets/api-tokens.json` (nur für den Besitzer lesbar, atomar geschrieben, symbolische Links werden abgelehnt). Weder `list` noch die API zeigen ein Token oder seinen Hash.
- Eine Token-Datei, die sich nicht lesen lässt (fehlerhaft, ein Link, zu groß), hält den Node nicht auf. Mit `api.enabled: false` schreibt er eine Warnung ins Log, bei eingeschalteter API einen Fehler, und läuft dann ohne API weiter, so wie bei unbrauchbarem TLS-Material. `api token create`, `list` und `revoke` scheitern dann mit dem Grund (`secrets/api-tokens.json kann nicht gelesen werden (…)`) und versuchen es bei jedem Aufruf neu. Sobald die Datei repariert ist oder gelöscht wurde (dann ohne Tokens), funktionieren sie wieder. Die API selbst kommt mit dem nächsten Start des Nodes zurück.
- Die Prüfung vergleicht den Hash in konstanter Zeit (`MessageDigest.isEqual`) mit allen gespeicherten Hashes und bricht beim ersten Treffer nicht ab.
- Ein Widerruf wirkt sofort. Die nächste Anfrage scheitert, offene WebSockets dieses Tokens werden mit Code `1008` geschlossen.
- **Laufzeit:** Ein Token kann eine Laufzeit von 1h bis 3650d bekommen (`12h`, `90d`). Ab `expires-at` scheitert es mit `401` wie ein widerrufenes, offene WebSockets schließen sich innerhalb von 15 Sekunden mit `1008`. In `list` bleibt es (als abgelaufen markiert), bis es widerrufen wird, damit sein Name nicht versehentlich neu vergeben wird. Ohne Laufzeit gilt ein Token, bis es widerrufen wird.
- **Über die API:** `POST /api/v1/admin/tokens` mit `{"name": "ci", "scopes": ["services:read"], "expires-in": "90d"}` erstellt ein Token. Die Antwort ist die einzige Stelle, an der die API je ein Token zeigt. Ein vergebener Name oder das 101. Token ergibt `409 conflict`. Ein `admin`-Token kann weitere `admin`-Tokens erstellen. `admin` bekommt also nur, wer auch an die Konsole des Nodes darf.
- Höchstens 100 Tokens. Namen folgen der Regel für Gruppennamen `[a-z0-9][a-z0-9_-]{0,31}`.
- Wo das angezeigte Token landet: Der Befehl schreibt auf die Konsole, nicht ins Log. Logdateien und Journal bekommen es also nicht. Unter systemd hat die Cloud gar keine Konsole, lege Tokens dann in einer `tmux`- oder `screen`-Sitzung an ([Betrieb](operations.de.md)). In Docker ist die Konsole eines angehängten Containers (`docker attach`) seine Standardausgabe, `docker logs` behält das Token also. Leere oder rotiere diese Logs, oder widerrufe das Token und lege ein neues an, wenn die Logs geteilt werden.

Das Token geht als `Authorization: Bearer vct_...` mit. Browser können am WebSocket keine Header setzen. Deshalb nimmt der Event-Stream das Token auch als Subprotokoll `vectolyn.bearer.<token>` neben `vectolyn.v1` an. Der Server antwortet nur mit `vectolyn.v1` und gibt das Token nie zurück. Bietet ein Upgrade Subprotokolle an, aber nicht `vectolyn.v1`, kommt `400 subprotocol-required`.

### Erster Administrator

Am Ende der ersten Einrichtung zeigt die Konsole einen einmaligen Einrichtungs-Token für den dort genannten Administrator ([Einrichtung](setup.de.md#erster-administrator)). `api token setup [name]` erstellt einen neuen (Standardname `admin`). Er besteht aus `vcs_` und 43 Zeichen aus 32 Bytes `SecureRandom`, gilt 24 Stunden und genau einmal. Gespeichert werden nur sein SHA-256, der Name und das Ablaufdatum, in `<home>/secrets/setup-token.json` (nur für den Besitzer, atomar, keine Links). Ein neuer Einrichtungs-Token ersetzt den alten.

```sh
curl -X POST -H "Authorization: Bearer vcs_..." https://cloud.example.org:8443/api/v1/setup/claim
# 201 {"id":"a1b2c3d4","name":"owner","scopes":["admin"],"created-at":"...","token":"vct_..."}
```

- Der Einrichtungs-Token gilt nur für diese eine Anfrage. An jedem anderen Endpunkt oder mit Query-String ist er ein falsches Token (`401`, zählt für die Sperre nach falschen Tokens). Ein API-Token bekommt an diesem Endpunkt `403 setup-token-required`.
- Der Tausch schreibt zuerst das API-Token. Scheitert das (`500 io-error`), bleibt der Einrichtungs-Token gültig und der Tausch lässt sich wiederholen. Sobald das API-Token existiert, ist der Einrichtungs-Token sofort ungültig. Zweimal geht also nie, ein zweiter Versuch bekommt `401`. Danach wird seine Datei gelöscht. Scheitert das Löschen, gelingt der Tausch trotzdem und der Node protokolliert einen Fehler, denn mit der Datei wäre der Einrichtungs-Token nach einem Neustart wieder gültig (Datei löschen oder den Token mit `api token setup` ersetzen). Das API-Token hat den Scope `admin` und den Namen des Administrators, mit `-2`, `-3`, … falls es diesen Namen schon gibt.
- Nur diese Antwort enthält das API-Token (`Cache-Control: no-store`). Geht es verloren: an der Konsole widerrufen und einen neuen Einrichtungs-Token erstellen.
- Das Web-Panel (Stufe 8) nutzt später dieselbe Anfrage für die erste Anmeldung.

### Scopes

| Scope | Erlaubt |
|---|---|
| `services:read` | Dienste auflisten und lesen; Dienst-, Spieler- und Skalierungs-Events |
| `services:write` | starten, stoppen, neu starten, Konsolenbefehl senden |
| `config:read` | `cloud.yml`, Gruppen, Tasks, Server-Schalter, Warnungen und Setup-Status lesen |
| `config:write` | Gruppen und Tasks schreiben und löschen, `wanted` setzen, `cloud.yml` außer `api` und `database` schreiben, neu laden |
| `backups:read` | Backups auflisten |
| `backups:write` | Backup erstellen |
| `node:read` | Node-Status; Metrik-Events |
| `templates:read` | Templates und ihre Dateien auflisten, eine Datei lesen (nie Secrets) |
| `templates:write` | Text-Konfigurationsdateien von Templates anlegen und ersetzen (kein Code, keine neuen Secret-Platzhalter) |
| `admin` | alles oben und die Token-Verwaltung unter `/admin/tokens` |

Ein Schreib-Scope schließt den Lese-Scope nicht ein. Fehlt der Scope, kommt `403 missing-scope`. Für Änderungen an `api` oder `database` in `cloud.yml` braucht es `admin`. Diese Abschnitte bestimmen, wer den Node erreicht und welches Image `docker compose` mit dem Root-Passwort der Datenbank startet.

**Was die Schreib-Scopes erreichen.** `config:write` ist keine Shell auf dem Node: Die JVM-Flags begrenzt die [Allowlist](config.md#jvm-flag-allowlist), Templates sind Ordner im Cloud-Home, Software und Plugins kommen nur aus den Download-Quellen mit Prüfsumme. `services:write` darf jeden Konsolenbefehl an einen Server schicken, also so viel wie ein Operator an der Serverkonsole. Bieten Plugins Konsolenbefehle für Dateien oder Skripte, reicht das weiter. Diesen Scope bekommen nur Skripte, denen man auch die Serverkonsole geben würde. `GET /api/v1/tokens/self` geht mit jedem gültigen Token und zeigt ID, Name, Scopes, Erstellungszeit und Ablauf.

Die Rechteprüfung ist für jede Route und jeden Scope gegen einen laufenden Server getestet (`ScopeMatrixTest`): Ein Token mit einem Scope bekommt genau dort `403 missing-scope`, wo die Route einen anderen braucht, und `admin` kommt überall durch.

## Endpunkte

Alle Pfade beginnen mit `/api/v1`. Bodys sind `application/json` in UTF-8, mit denselben Namen (kebab-case) und denselben Records wie die YAML-Dateien ([config.md](config.md)). Die vollständige Tabelle steht in [api.md](api.md#endpoints).

- **Dienste:** `GET /services`, `POST /services` mit `{"task": "lobby"}` (Antwort `201`), `GET /services/{id}`, `POST /services/{id}/stop`, `POST /services/{id}/restart`, `POST /services/{id}/command` mit `{"command": "say hi"}` (Antwort `202`, 1 bis 1024 Zeichen ohne Steuerzeichen).
- **Gruppen und Tasks:** `GET /groups`, `GET|PUT /groups/{name}`, `DELETE /groups/{name}?confirm={name}`, `PUT /groups/{name}/wanted` mit `{"wanted": false}`. Für `/tasks` gilt dasselbe.
- **Nodes:** `GET /nodes`, `GET|PUT /nodes/{name}` (Einträge aus `nodes.yml`, mit `ETag`).
- **Server-Schalter:** `GET /servers`, `PUT /servers/{kind}` mit `{"wanted": true}`.
- **Konfiguration:** `GET|PUT /config` (`cloud.yml`), `POST /config/reload`, `GET /config/warnings`.
- **Node und Setup:** `GET /node`, `GET /setup`.
- **Backups:** `GET /backups`, `POST /backups` mit `{"services": [...], "database": true, "note": "..."}`.
- **Templates:** `GET /templates`, `GET /templates/{template}/files`, `GET|PUT /templates/{template}/files/{path}` (siehe unten).
- **Events:** `GET /events` als WebSocket.

Jeder Schreibzugriff läuft über `ConfigService.apply`. Geprüft wird die ganze Konfiguration: Ports, EULA vor `setup-complete`, TLS für eine öffentliche API und die Allowlist der JVM-Flags. Danach wird atomar geschrieben, und die Listener des Nodes sehen die Änderung so, als wäre sie an der Konsole eingegeben worden. Bei einem Fehler wird nichts geschrieben, die Antwort ist `422 invalid-config` mit der Fehlerliste.

**Wanted.** Es gibt zwei Schalter, und eine Gruppe läuft nur, wenn beide an sind: das eigene `wanted` der Gruppe in `groups/<Name>.yml` und der Schalter ihrer Serverart in `cloud.yml` (`servers:`, die Serverart, deren Template-Ordner die Gruppe nutzt). `PUT /groups/{name}/wanted` setzt den Schalter der Gruppe. `PUT /tasks/{name}/wanted` setzt den Schalter der Gruppe des Tasks, denn Tasks haben keinen eigenen. Das betrifft also alle Tasks dieser Gruppe. Eine PandaSpigot-Gruppe mit eigenem Template folgt `servers.legacy-server`. Der 1.8.8-Teil des Netzwerks (PandaSpigot sowie die Templates `Legacy-Proxy` und `Legacy-Server`) braucht zusätzlich `legacy-proxy.enabled`, und das Datenbank-Template läuft nie als Dienst. Nach dieser Regel plant auch der Node. Die Antwort zeigt beide Schalter und `runs`, das die ganze Regel anwendet. `PUT /servers/{kind}` setzt den Schalter der Serverart.

**Löschen.** `DELETE` verlangt den Namen ein zweites Mal als `?confirm=<name>`, sonst kommt `400 confirmation-required`.

**Gleichzeitige Änderungen.** `GET` auf eine Gruppe, einen Task oder `cloud.yml` liefert ein `ETag`. Wer es bei `PUT` oder `DELETE` als `If-Match` mitschickt, bekommt `412 precondition-failed`, falls sich das Dokument inzwischen geändert hat. Geschrieben wird dann nichts. Die Prüfung läuft zusammen mit dem Schreiben: Von mehreren Schreibern mit demselben `ETag` kommt genau einer durch.

**Tasks.** `PUT /tasks/{name}` erwartet den ganzen Task im aktuellen Format (`schema-version: 2`) mit `scaling.static-services` (Pflicht) und `scaling.scale-down-below` (Standard 50); das Beispiel steht in [api.md](api.md). Derselbe Validator wie für die Datei antwortet mit `422` und `static-above-min` (statisch über min) oder `scale-down-not-below-up` (`scale-down-below` nicht kleiner als `scale-up-at`). Ein Body mit `schema-version: 1` (mit `mode`) wird genau wie eine Datei migriert (siehe [Konfiguration](config.md#from-schema-version-1)) und als Version 2 gespeichert; die Antwort zeigt den migrierten Task. Einen Body, den der Leser ablehnt (unbekanntes Feld, falscher Typ, fehlendes Pflichtfeld, ungültiges `mode`), beantwortet die API mit `400` und `errors` mit Feld und Code, etwa `required` oder `invalid-value`. `POST /services` mit einem Task startet den niedrigsten gestoppten statischen Dienst des Tasks, sonst einen neuen statischen, solange der Task weniger als `static-services` hat, sonst einen dynamischen. Siehe [Skalierung](scaling.de.md#statisch-und-dynamisch-in-einem-task).

**Nodes.** `PUT /nodes/{name}` erwartet den ganzen Node wie in `nodes.yml`, zum Beispiel `{"host": "127.0.0.1", "port": 7010, "service-ports": {"first": 30000, "last": 30999}, "max-memory": "8G", "memory-reserve": {"system": "2G"}}`. `id` kann fehlen; ist sie gesetzt, muss sie zum Pfad passen, sonst `400 invalid-value`. `max-memory` (mindestens 512M) und jedes Feld von `memory-reserve` (1M bis 64G; Defaults `system` 1G, `node-overhead` 256M, `database` 512M) sind optional. Ein weggelassenes Feld wird aus der Datei entfernt, dann gilt der Default. Derselbe Validator wie für die Datei antwortet mit `422` (etwa `out-of-range` bei `nodes[0].memory-reserve.system`). `ETag` und `If-Match` funktionieren wie bei Gruppen. Ein `DELETE` gibt es nicht; Nodes entfernt man in der Datei.

**Ausweichgruppe.** `fallback-group` in `PUT /config` nennt die Gruppe, auf die die Proxys einen gekickten Spieler schicken, wenn seine eigene Gruppe keinen freien Server hat, zum Beispiel `"fallback-group": "hub"`. Das Feld ist optional (Standard `lobby`, dann fehlt es in der Antwort); eine Gruppe, die es nicht gibt, beantwortet die API mit `422 invalid-config` und `unknown-group`. Ohne das Feld gilt wieder der Standard. Die Proxys übernehmen den Wert bei ihrem Start (siehe [bridges.md](bridges.md#catching-kicks)).

**Powered-by-Zeile.** `branding.powered-by` in `PUT /config` (zum Beispiel `"branding": {"powered-by": false}`) schaltet die TAB-Fußzeile „Powered by VectolynCloud“ und `/version` auf den Proxys ein oder aus. Fehlt der Abschnitt oder der Schlüssel (`"branding": {}`), gilt `true`. Dienste übernehmen den Wert bei ihrem nächsten Start (siehe [config.md](config.md#cloudyml)).

**Gruppen.** `drain-timeout` ist optional (5s bis 10m, Default 30s), zum Beispiel `"drain-timeout": "45s"`; außerhalb der Grenzen antwortet die API mit `422 out-of-range`.

**Templates.** Die Endpunkte unter `/templates` braucht der Datei-Editor des Web-Panels: Sie listen die Template-Ordner in `local/templates/`, zeigen eine Datei und speichern sie zurück. `{path}` ist der Rest der URL nach `/files/`, die Segmente mit `/` getrennt und nicht prozentkodiert, etwa `/api/v1/templates/Lobby-Server/files/default/plugins/LuckPerms/config.yml`. Der Router lässt Segmente aus Buchstaben, Ziffern, `_` und `-` durch, bis zu acht Teile mit je einem Punkt dazwischen. Eine Datei mit anderem Namen (Leerzeichen, Umlaut) steht in der Liste, lässt sich aber nicht ansprechen. Danach gelten dieselben Regeln wie beim Cluster-Spiegel (`TemplatePaths` in `cloud/config`): kein `..`, `.`, leeres Segment, Backslash, Doppelpunkt oder Windows-Gerätename, höchstens 240 Bytes und 16 Segmente, und nichts, was nach Secret aussieht (`forwarding.secret`, `*.pem`, `*.key`, `.env`, `secrets/`, `.git/` und so weiter). So ein Pfad bekommt `400 invalid-path`, bevor die Platte berührt wird; in Listen fehlen diese Dateien. Links, Junctions und Sonderdateien werden nie verfolgt; liegt eine im Weg, kommt `409 not-a-regular-file`.

- **Lesen** liefert UTF-8-Text unverändert und alles andere (auch Text mit NUL-Byte) als Base64, bis 1 MiB (darüber `413 file-too-large`). Das `ETag` gehört zu den Bytes der Datei.
- **Schreiben** prüft das `ETag`: Ohne `If-Match` entsteht nur eine neue Datei, eine vorhandene bekommt `428 precondition-required`. Mit dem `ETag` aus dem `GET` (oder `*`) wird die Datei ersetzt; hat sie sich inzwischen geändert, kommt `412 precondition-failed`. Prüfen und Schreiben laufen unter einer Sperre, von zwei Editoren mit demselben `ETag` kommt also genau einer durch. Eine Datei darf höchstens 48 KiB groß sein (die API liest Strings bis 64 KiB, das sind 48 KiB in Base64). Fehlende Ordner werden angelegt, die Datei wird atomar ersetzt.
- **Was geschrieben werden darf.** `templates:write` deckt nur Text-Konfiguration ab. Ein Token kann also keinen Code ablegen, den ein Server lädt, und kein Secret in eine Datei holen. Die Regeln stecken in `TemplatePaths.isEditablePath` und `TemplateFiles.write` in `cloud/config` und gelten damit für jeden Aufrufer. Jede Ablehnung antwortet mit `422`, geschrieben wird nichts:
  - `422 file-not-editable`: Der Dateiname muss auf `.yml`, `.yaml`, `.toml`, `.json`, `.properties`, `.txt` oder `.conf` enden (Groß- und Kleinschreibung egal), und kein Ordner auf dem Weg darf `plugins`, `mods`, `libraries`, `versions`, `bundler` oder `cache` heißen (auch `Plugins/`, auch kein Windows-Kurzname wie `LIBRAR~1`). Jars, Klassen, Archive, Skripte (`.sh`, `.bat`, `.cmd`, `.ps1`), native Bibliotheken, `.ini` und `.sql` fallen damit heraus. Plugin-Konfigurationen unter `plugins/` lassen sich weiter lesen, geändert werden sie auf der Platte, nicht über die API.
  - `422 binary-content`: Der Inhalt muss UTF-8-Text ohne NUL sein. `"encoding": "base64"` wird grundsätzlich abgelehnt; beim Lesen kommen Binärdateien weiter als Base64.
  - `422 secret-placeholder`: Ein Secret-Platzhalter darf im neuen Inhalt nicht öfter vorkommen als in der Datei, wie sie jetzt ist. Secret-Platzhalter sind `{{VECTOLYN_...}}` mit einem Schlüssel aus `secrets/vectolyn.env`, den der Renderer einsetzt (`VECTOLYN_FORWARDING_SECRET`, `VECTOLYN_LEGACY_FORWARDING_SECRET`, `VECTOLYN_MANAGEMENT_SECRET`, `VECTOLYN_DB_PASSWORD`, `VECTOLYN_DB_ROOT_PASSWORD`, `VECTOLYN_FLOODGATE_KEY_B64`), oder mit einem Namen, der `SECRET`, `PASSWORD`, `PASS`, `KEY` oder `TOKEN` enthält; maskierte (`{{{{VECTOLYN_...}}`) zählen mit. Eine neue Datei enthält also keinen, und eine Änderung darf die Platzhalter einer mitgelieferten Datei behalten oder entfernen, aber keinen hinzufügen. Verschiebt jemand einen vorhandenen Platzhalter innerhalb derselben Datei, fällt das nicht auf; solche Dateien vor der Vergabe von `templates:write` prüfen.
- **Cluster.** Auf einem Mitglied kommen die Templates vom Head (siehe [cluster.md](cluster.md)), dort antwortet `PUT` mit `409 managed-by-head`. Lesen geht.
- Dienste übernehmen ein geändertes Template, wenn sie das nächste Mal daraus vorbereitet werden ([template.md](template.md)); ein laufender Dienst bleibt unberührt.

**Herunterfahren.** Während der Node herunterfährt, beantworten Start, Neustart, Reload und das Erstellen eines Backups die Anfrage mit `503 shutting-down`. Lesen und Stoppen gehen weiter.

**Backups.** `BackupServiceAccess` setzt `BackupAccess` über den `BackupService` aus cloud-backup um. Backups über die API haben den Grund `manual`, wie `backup create` an der Konsole. Läuft schon ein Backup, kommt `409`, bei einem unbekannten Dienst `422`. Antworten enthalten die Backup-ID, nie einen Pfad. Andere Fehler (Dateien, Datenbank-Dump, kaputtes Archiv) beantwortet die API mit `503 unavailable` und nur dem Grund, etwa `(io)`. Die Einzelheiten stehen im Log des Nodes. Solange der Node den Adapter nicht übergibt, antworten beide Endpunkte mit `501 not-available`.

### Seiten

Die Listen (`GET /services`, `/groups`, `/tasks`, `/nodes`, `/backups`) sind nach ihrem Schlüssel sortiert: Service-ID, Name der Gruppe, des Tasks oder des Nodes, Backup-ID (die nach Zeit sortiert). Ohne `limit` enthält die Antwort die ganze Liste. Mit `?limit=n` (1 bis 500) enthält sie höchstens `n` Einträge, `?after=<schlüssel>` beginnt nach diesem Schlüssel. Der Body behält seine Form (ein Array, bei Gruppen und Tasks ein Objekt nach Namen). Ein Client, der nicht blättert, merkt also keinen Unterschied. Zwei Header tragen den Stand:

- `X-Total-Count`: Einträge der ganzen Liste.
- `Link: </api/v1/services?limit=50&after=lobby-50>; rel="next"` (RFC 8288), solange weitere folgen. Die letzte Seite hat keinen.

Der Cursor ist ein Schlüssel, keine Position. Ein Eintrag, den es während beider Anfragen gibt, wird weder übersprungen noch doppelt geliefert, auch wenn dazwischen andere hinzukommen oder wegfallen. Ein ungültiges `limit` oder `after` ergibt `400 invalid-query`.

### Fehler

Jeder Fehler hat denselben Body: `code`, `message` und bei Konfigurationsfehlern `errors` mit `file`, `path`, `line`, `code`, `message` und `message-de`. Dateinamen (`file`, `files` beim Reload) sind relativ zu `config/` (`groups/lobby.yml`), wie in der Konsole. Meldungen nennen Felder und Regeln, nie Werte. Meldungen des Nodes laufen durch dieselbe Schwärzung wie die Konsolenausgabe und werden auf 300 Zeichen gekürzt. Die Liste der Codes steht in [api.md](api.md#errors).

### Strenge Eingaben

- JSON wird nach denselben Regeln gelesen wie die YAML-Dateien. Unbekannte Felder, doppelte Felder und falsche Typen werden abgelehnt (`400`).
- Ein Body hat höchstens 256 KiB (`413`), auch bei Chunked-Encoding. Der Server zählt beim Lesen mit. Schließt eine Antwort die Verbindung, beendet der Server zuerst seine Senderichtung und verwirft bis zu 2 Sekunden lang, was der Client noch schickt. So kommt beim Client die `413` an statt eines Verbindungsabbruchs.
- Bodys brauchen `Content-Type: application/json` (`415`).
- Pfad und Query werden vor dem Routing geprüft: druckbares ASCII, kein `%` im Pfad, keine Segmente `.` oder `..`, IDs `[a-z0-9][a-z0-9_-]{0,63}`, Namen `[a-z0-9][a-z0-9_-]{0,31}`, Query-Schlüssel klein geschrieben und jeder nur einmal.
- HTTP-Pipelining wird nicht unterstützt. Pro Verbindung läuft eine Anfrage nach der anderen.

## Live-Events: `GET /api/v1/events`

Ein WebSocket mit Textframes. Filter sind optional: `?types=service-state,metrics` und `?services=lobby-1,survival-1`. Ein unbekannter Typ ergibt `400`.

| Typ | Scope | Inhalt |
|---|---|---|
| `service-state` | `services:read` | ein Dienst hat seinen Zustand gewechselt |
| `service-output` | `services:read` | eine Zeile, die ein Dienst ausgegeben hat (sein Log) |
| `player` | `services:read` | ein Spieler kam, wechselte oder ging |
| `scaling` | `services:read` | eine Entscheidung des Autoscalers ([scaling.de.md](scaling.de.md#entscheidungen-als-daten)) |
| `metrics` | `node:read` | der Node-Status (wie `GET /node`) alle 10 s |
| `node-log` | `admin` | eine Zeile aus dem Log des Nodes, ab INFO |

Der Scope wird beim Handshake je Typ geprüft. Fragt `types` einen Typ an, den das Token nicht sehen darf, kommt `403 missing-scope`. Ohne `types` bekommt der Stream alle Typen, die das Token sehen darf; darf es keinen, kommt `403`. Unbekannte Typen sollte ein Client überspringen, innerhalb von `vectolyn.v1` können neue hinzukommen. Der Filter `services` gilt für Dienst-, Spieler- und Skalierungs-Events (ein `hold` nennt keinen Dienst und kommt nur bei einem Stream ohne Filter an); `metrics` und `node-log` gehören zum Node und kommen immer durch.

```json
{"type": "service-output", "time": "2026-10-29T10:00:00Z", "data": {"service": "lobby-1", "line": "Done (3.2s)!"}}
```

- Ausgabezeilen und Zeilen aus dem Node-Log werden geschwärzt, bevor sie den Node verlassen. Jedes Secret aus `vectolyn.env` wird zu `***`, genau wie in der Konsole. Eine Node-Log-Zeile enthält nie einen Stacktrace, nur den Namen der Ausnahme; Steuerzeichen werden zu Leerzeichen, nach 2000 Zeichen wird gekürzt. Einträge von Netty und der API selbst werden nicht gestreamt, damit sich der Stream nicht selbst füttert.
- Metriken werden nur gesammelt, solange ein Stream sie haben will.
- Ein langsamer Client bremst den Node nicht. Solange sein Socket nicht beschreibbar ist, werden Events für ihn verworfen und gezählt. Danach kommt ein einzelnes Event `dropped` mit der Anzahl.
- Der Server pingt alle 30 s und schließt einen Stream, von dem 90 s lang nichts kam. Ein Widerruf schließt mit `1008`, das Herunterfahren mit `1001`.
- Ein Handshake aus dem Browser mit einem `Origin`, der nicht in `api.cors-origins` steht, wird mit `403` abgelehnt.
- Höchstens 32 Streams gleichzeitig.
- **Lasttest:** `EventStreamLoadTest` öffnet 260 Streams in Wellen zu je 40 gegen ein Limit von 200 und schickt dann von 4 Threads gleichzeitig 10.000 Events an alle. Geprüft wird, dass genau 200 aufgehen, die übrigen 60 `503` bekommen, die REST-API weiter antwortet, jeder Stream jedes Event verbucht (empfangen oder in `dropped` gezählt) und sich jeder Stream sauber schließt. Die Messwerte stehen im JUnit-XML jedes CI-Laufs und in `cloud/api/build/reports/api-load/event-streams.md`.

## GraphQL: `POST /api/v1/graphql`

Ein zweiter Weg zu denselben Endpunkten, für Clients, die mehrere Bereiche in einer Anfrage oder ein typisiertes Schema wollen. Das Schema steht in [`schema.graphqls`](../../cloud/api/src/main/resources/net/vectolyn/cloud/api/schema.graphqls): Queries für jeden Bereich (Dienste, Gruppen, Tasks, Nodes, Server, `cloud.yml`, Node-Status, Setup, Backups, Tokens), eine Mutation für jede Änderung und die Subscription `events`.

```sh
curl -s -H "Authorization: Bearer $VECTOLYN_TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"{ services { totalCount items { id state } } nodeStatus { servicesRunning } }"}' \
  http://127.0.0.1:8085/api/v1/graphql
```

- **Dieselben Regeln wie REST.** Jedes Feld baut die REST-Anfrage, die in seiner Beschreibung steht, und führt diese Route mit dem Token des Aufrufers aus: mit ihrem Scope, ihrer Prüfung, `If-Match` (Argument `ifMatch`) und ihren Fehlern. Ein abgelehntes oder fehlgeschlagenes Feld ist `null` und hat einen Fehler mit `extensions.code` und `extensions.status` (zum Beispiel `missing-scope`, `403`); die übrigen Felder des Dokuments werden trotzdem beantwortet, deshalb sind die Wurzelfelder nullable. Argumente, die Teil eines Pfads werden (IDs, Namen, `after`), müssen kleingeschriebene Namen sein, sonst `invalid-value`.
- **Formen.** Typisierte Objekte nutzen camelCase (`startedAt`, `heapUsedBytes`); Konfigurationsdokumente behalten ihre REST-Form als Skalar `JSON` (Schlüssel in kebab-case) zusammen mit ihrem `etag`. Listen nehmen `limit` und `after` und antworten mit `totalCount`, `next` und `items`.
- **Grenzen**, alle geprüft, bevor ein Feld läuft: 16.384 Zeichen, 2.000 Parser-Tokens, Tiefe 8, Komplexität 200 (eins je Feld) und höchstens 20 Felder, die Daten lesen oder ändern. Die Anfrage selbst kostet eine Anfrage aus dem Ratenlimit des Tokens, jedes solche Feld nach dem ersten eine weitere (`extensions.retryAfter`, wenn es aufgebraucht ist).
- **Introspection** (`__schema`, `__type`) ist aus, solange in `cloud.yml` nicht `api.graphql-introspection: true` steht (Konsole: `config set api.graphql-introspection true`). Für die Entwicklung einschalten, im Betrieb aus lassen; die Schema-Datei ist ohnehin öffentlich.
- **Subscriptions** laufen über den WebSocket `GET /api/v1/graphql` mit dem Subprotokoll `graphql-transport-ws` (das Protokoll der Client-Bibliothek `graphql-ws`). Der Token wird beim Handshake geprüft wie bei `/events` (Header, oder aus dem Browser als Subprotokoll `vectolyn.bearer.<token>`). Innerhalb von 10 s `connection_init` senden, dann `subscribe` mit zum Beispiel `subscription { events(types: ["service-state"]) { type time data } }`. Jeder Ereignistyp braucht seinen Scope wie bei `/events`. Queries und Mutations gehen auf dem Socket auch. Höchstens 16 Operationen je Socket, jedes `subscribe` kostet eine Anfrage aus dem Ratenlimit. Der Socket zählt zu den 32 WebSockets und schließt mit `1008`, wenn sein Token widerrufen wird oder abläuft. Protokollfehler schließen mit den Codes des Protokolls (`4400`, `4401`, `4408`, `4409`, `4429`).

## gRPC: `api.grpc-port`

Dieselben Endpunkte für Clients, die lieber mit generierten Stubs arbeiten. Der Dienst ist `vectolyn.api.v1.CloudService` in [`cloud.proto`](../../cloud/api/src/main/resources/vectolyn/api/v1/cloud.proto), auch veröffentlicht unter `https://vectode.github.io/VectolynCloud/api/vectolyn/api/v1/cloud.proto`. gRPC hat einen eigenen Port, weil HTTP/2 auf dem REST-Port ändern würde, was Browser und `curl` dort aushandeln. Er ist aus, bis `api.grpc-port` in `cloud.yml` gesetzt ist (Konsole: `config set api.grpc-port 8086`), und nutzt `api.bind` und die TLS-Einstellung der API. Mit TLS handelt der Listener `h2` über ALPN aus; auf Loopback ohne TLS spricht er HTTP/2 mit Prior Knowledge (`grpcurl -plaintext`, `grpc.insecure_channel`).

```sh
grpcurl -plaintext -import-path cloud/api/src/main/resources -proto vectolyn/api/v1/cloud.proto \
  -H "authorization: Bearer $VECTOLYN_TOKEN" -d '{}' 127.0.0.1:8086 vectolyn.api.v1.CloudService/ListServices
```

```python
# python -m grpc_tools.protoc -I cloud/api/src/main/resources --python_out=. --grpc_python_out=. vectolyn/api/v1/cloud.proto
import grpc
from vectolyn.api.v1 import cloud_pb2, cloud_pb2_grpc

with grpc.insecure_channel("127.0.0.1:8086") as channel:
    stub = cloud_pb2_grpc.CloudServiceStub(channel)
    auth = [("authorization", f"Bearer {token}")]
    print(stub.ListServices(cloud_pb2.ListRequest(limit=50), metadata=auth))
    for event in stub.Events(cloud_pb2.EventsRequest(types=["service-state"]), metadata=auth):
        print(event.type, event.json)
```

- **Dieselben Regeln wie REST.** Jede Methode führt den REST-Endpunkt aus, der in ihrem Kommentar in der Proto-Datei steht, mit dem Token des Aufrufers (Metadaten `authorization: Bearer vct_...`): mit seinem Scope, seiner Prüfung, `If-Match` (`if_match`) und seinen Fehlern. Konfigurationsdokumente gehen als JSON-Text in ihrer REST-Form zusammen mit ihrem `etag`; Listen nehmen `limit` und `after` und antworten mit `total_count`, `next` und `items`.
- **Fehler** sind ein gRPC-Status in den Trailern, mit `grpc-message` und dem REST-Code im Trailer `vectolyn-code`: `400`, `415` und `422` werden `INVALID_ARGUMENT`, `401` `UNAUTHENTICATED`, `403` `PERMISSION_DENIED`, `404` `NOT_FOUND`, `408` `DEADLINE_EXCEEDED`, `409` und `412` `FAILED_PRECONDITION`, `413` und `429` `RESOURCE_EXHAUSTED`, `503` `UNAVAILABLE`, `500` `INTERNAL`. Eine unbekannte Methode ist `UNIMPLEMENTED`, eine komprimierte Nachricht ebenso (nur `identity` wird unterstützt); ein kaputter Rahmen oder eine kaputte Nachricht ist `INTERNAL` mit `vectolyn-code: invalid-message`. Eine Anfrage, die nicht `POST` ist, bekommt HTTP `405`, eine mit anderem Content-Type als `application/grpc` oder `application/grpc+proto` HTTP `415` (gRPC-Web wird nicht unterstützt). Ein abgelehnter Stream wird zurückgesetzt und lässt sich nicht offen halten. `UNIMPLEMENTED` ist der Standardstatus für alles, was der Listener nicht umsetzt: eine unbekannte Methode und eine komprimierte Nachricht. Es ist der gRPC-Standardcode für diesen Fall, kein Fehler der Cloud.
- **Prüfungen, bevor etwas läuft**, in dieser Reihenfolge: Ratenlimit der Client-Adresse, Methode, Content-Type, bekannte Methode, zu viele falsche Tokens, der Token, das Ratenlimit des Tokens. Ein Aufruf kostet eine Anfrage aus dem Ratenlimit des Tokens. Die Anfrage ist genau eine Nachricht von höchstens der Body-Grenze (`maxBodyBytes`) und muss innerhalb des Idle-Timeouts vollständig ankommen (sonst `DEADLINE_EXCEEDED`, `request-timeout`). Die unfertigen Anfragen einer Verbindung teilen sich eine Body-Grenze (sonst `UNAVAILABLE`, `busy`), so hält eine gRPC-Verbindung nicht mehr Anfragespeicher als eine REST-Verbindung. Große Uploads parallel brauchen je eine eigene Verbindung oder einen neuen Versuch.
- **`Events`** streamt die Ereignisse von `/events`: `types` (jeder Typ braucht seinen Scope; leer heißt jeder Typ, den der Token sehen darf) und `services`. Jedes `Event` hat `type`, `time` und die Ereignisdaten als JSON in `json`. Ein langsamer Client verliert Ereignisse und bekommt ein Ereignis vom Typ `dropped` mit der Anzahl. Ein Stream zählt zu den 32 Event-Streams und endet mit `UNAUTHENTICATED`, wenn sein Token widerrufen wird oder abläuft, und mit `UNAVAILABLE`, wenn der Node stoppt.
- **Verbindungen** zählen zu denselben Grenzen wie HTTP-Verbindungen. Höchstens 16 Aufrufe laufen gleichzeitig auf einer Verbindung, Header sind auf 8 KiB begrenzt. Nur authentifizierte Aufrufe zählen als Aktivität: Eine Verbindung ohne einen solchen wird nach dem Idle-Timeout geschlossen, egal welche Frames der Client sendet (PING, SETTINGS, abgelehnte Streams). Eine Verbindung mit offenem `Events`-Stream wird stattdessen angepingt. Nettys Grenzen gegen Reset-, Steuerframe- und Leerframe-Fluten sind ausdrücklich gesetzt.
- **Warum nicht grpc-java.** Der Listener besteht aus Nettys HTTP/2-Codec und einem kleinen proto3-Codec ([`Proto`](../../cloud/api/src/main/java/net/vectolyn/cloud/api/grpc/Proto.java)), den die Proto-Datei selbst steuert. grpc-java brächte ein zweites, geshadetes Netty und von `protoc` generierte Klassen in den Build, die API braucht aber nur unäre Aufrufe und einen Server-Stream. Der Server liest `cloud.proto` beim Start und startet nicht, wenn seine Methodentabelle von der Datei abweicht. Der offizielle Python-Client (`grpcio`) wurde gegen den Listener getestet: unäre Aufrufe, Fehler mit Trailern und `Events`.

## Transportsicherheit

### Bind und TLS

Standard ist `127.0.0.1`. Jede Adresse außerhalb von Loopback, auch `0.0.0.0`, braucht `api.tls.enabled: true`. Sonst lehnt der Validator die Konfiguration ab (`api-tls-required`), und der Server verweigert zusätzlich den Start.

Mit TLS spricht der Server nur TLS 1.3 und 1.2 und sendet `Strict-Transport-Security`. Zum Keystore (`api.tls.keystore`, PKCS#12, relativ zum Home):

- **Eigenes Zertifikat:** Die PKCS#12-Datei an diesen Pfad legen und das Passwort in `secrets/vectolyn.env` als `VECTOLYN_API_KEYSTORE_PASSWORD` eintragen. Fehlt das Passwort, startet der Node die API nicht und nennt den fehlenden Schlüssel.
- **Selbst erzeugt:** Gibt es die Datei nicht, erzeugt der Node mit `keytool` aus dem JDK ein selbstsigniertes Zertifikat. Es nutzt EC P-256, gilt 825 Tage und enthält die Namen `localhost`, `127.0.0.1`, `::1` und die Bind-Adresse, sofern sie keine Wildcard ist. Das Keystore-Passwort ist zufällig und liegt in `secrets/api-keystore.password` (nur für den Besitzer lesbar). `keytool` bekommt es über eine Umgebungsvariable, nie über die Kommandozeile. `keytool` schreibt in einen neuen Ordner neben dem Keystore, den nur der Besitzer öffnen kann, und die fertige Datei wird in einem Schritt an ihren Platz verschoben. Solange sie entsteht, kann kein anderer Benutzer sie öffnen, und ein Absturz hinterlässt entweder keinen Keystore (er wird beim nächsten Start neu erzeugt) oder einen vollständigen. Bei jedem Start loggt der Node den SHA-256-Fingerprint, damit ein Client ihn pinnen kann.

### CORS

Standardmäßig aus. Es wird kein `Access-Control-*`-Header gesendet, Browser auf anderen Origins können Antworten nicht lesen. `api.cors-origins` nimmt bis zu 16 Origins auf (`https://host[:port]` oder `http://host[:port]`, ohne Pfad und ohne Wildcard). Für einen eingetragenen Origin beantwortet der Server Preflights und setzt `Access-Control-Allow-Origin` auf genau diesen Origin (`Vary: Origin`). An der Konsole: `config set api.cors-origins https://dashboard.example.org,http://localhost:5173`. Mit `config set api.cors-origins ""` ist CORS wieder aus.

### Limits

| Limit | Standard |
|---|---|
| Anfragen pro IP (IPv6 pro /64) | 600 pro Minute, Burst 120 |
| Anfragen pro Token | 300 pro Minute, Burst 60 |
| Fehlgeschlagene Anmeldungen pro IP | 10 pro Minute, danach `429`, bevor das Token überhaupt geprüft wird |
| Body | 256 KiB |
| Gleichzeitig bearbeitete Anfragen | 64 (darüber `503 busy`) |
| Verbindungen | 256, höchstens 32 pro IP |
| Event-Streams | 32 |
| Leerlauf | Verbindung wird nach 120 s geschlossen |

Bei einem Limit kommt `429 rate-limited` mit `Retry-After` in Sekunden. Jede Antwort auf eine Anfrage mit gültigem Token trägt `X-RateLimit-Limit` (Größe des Token-Eimers) und `X-RateLimit-Remaining` (was gerade noch drin ist). Ein Skript kann also langsamer werden, bevor es `429` bekommt.

Jeder Limiter merkt sich bis zu 10.000 Schlüssel (Adressen, Tokens) in der Reihenfolge der letzten Nutzung. Ein neuer Schlüssel darüber hinaus verdrängt den am längsten unbenutzten, in konstanter Zeit. Eine Flut neuer Adressen sperrt deshalb keine neuen Clients aus, und keine Anfrage muss die ganze Tabelle durchsuchen. Ein verdrängter Schlüssel beginnt wieder mit vollem Eimer. Davon hat ein Client nur etwas, wenn er mehr als 10.000 Adressen hat, und dann hat jede davon ohnehin einen eigenen Eimer.

Gezählt wird pro IPv4-Adresse und pro IPv6-/64. Ein Client hinter NAT64 (`64:ff9b::/96`, RFC 6052) zählt unter der IPv4-Adresse in den letzten 32 Bit. So teilen sich die Clients hinter einem NAT64-Gateway kein gemeinsames /64, und ein Client zählt über IPv4 und NAT64 gleich.

### Was nie in einer Antwort steht

Passwörter, das Datenbankpasswort, Tokens und ihre Hashes, das Keystore-Passwort und der Inhalt von `vectolyn.env`. `cloud.yml`, Gruppen und Tasks enthalten grundsätzlich keine Secrets.

### JVM-Flags

Gruppen lassen sich über die API schreiben. Darum gibt es für `process.jvm-flags` eine Allowlist, damit ein gestohlenes `config:write`-Token nicht zur Codeausführung auf dem Node führt. Details: [config.md](config.md#jvm-flag-allowlist).

## Einbindung in cloud-node

`cloud/api` hängt nicht von `cloud/node` ab. Der Node liefert ein `ApiBackend` und startet den Server. Die einzelnen Schritte stehen in [api.md](api.md#wiring-in-cloud-node): Tokens öffnen und den Befehl `api` registrieren, bei `api.enabled` TLS vorbereiten und `ApiServer` starten, Dienst-Events weiterreichen, `beginShutdown()` zu Beginn und `close()` am Ende von `CloudNode.close()`. Das macht `CloudNode` mit `ops.NodeApi`. Kann der Server seinen Port nicht binden (zum Beispiel weil er belegt ist), fährt er beide Event-Loop-Gruppen zusammen herunter, wartet auf das Ende ihrer Threads und meldet eine `IOException` mit dem Port. Der Node schreibt sie als Fehler ins Log und läuft ohne API weiter. Eine Ablehnung des Dienstkerns geht nur an den Client, wenn sie einer seiner eigenen kurzen Gründe ist und nichts enthält, das wie ein Pfad aussieht. Alles andere bleibt im Log des Nodes, der Client bekommt einen kurzen Text. `ApiSettings.of` lehnt `api.tls.enabled` ohne Schlüsselmaterial ab, ebenso Schlüsselmaterial bei ausgeschaltetem TLS. `runs` in den Wanted-Endpunkten ist `GroupRules.isWanted` aus cloud-config, also dieselbe Regel, nach der der Node plant.
