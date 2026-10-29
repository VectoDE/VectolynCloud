# VectoraCloud-REST-API

Modul `VectoraCloud/cloud-api`. Eine JSON-API über HTTP/1.1 und ein WebSocket für Live-Events, gedacht für das Dashboard (Etappe 8) und für Skripte. Die maschinenlesbare Beschreibung steht in [`api.openapi.yaml`](api.openapi.yaml), die ausführliche englische Seite in [api.md](api.md).

Die API ist standardmäßig aus. Sie startet mit dem Node, wenn in `cloud.yml` `api.enabled: true` steht:

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

Jede Anfrage braucht ein Token. Ohne Token, mit einem falschen oder einem widerrufenen kommt `401` mit `WWW-Authenticate: Bearer`. Einen Endpunkt ohne Anmeldung gibt es nicht.

Tokens verwaltet man an der Konsole des Nodes. Der ganze Befehl `api` gilt als sensibel, keine seiner Zeilen landet in der History.

```text
vectora> api token create dashboard services:read,config:read,node:read
vectora> api token list
vectora> api token revoke dashboard          # oder die ID; Aliase: delete, remove
vectora> api scopes
```

- Format: `vct_` und 43 Zeichen aus `[A-Za-z0-9_-]` (32 Zufallsbytes aus `SecureRandom`, Base64url).
- `create` zeigt das Token genau einmal an. Gespeichert wird nur der SHA-256-Wert, in `<home>/secrets/api-tokens.json` (nur für den Besitzer lesbar, atomar geschrieben, symbolische Links werden abgelehnt). Weder `list` noch die API zeigen ein Token oder seinen Hash.
- Die Prüfung vergleicht den Hash in konstanter Zeit (`MessageDigest.isEqual`) mit allen gespeicherten Hashes und bricht beim ersten Treffer nicht ab.
- Ein Widerruf wirkt sofort. Die nächste Anfrage scheitert, offene WebSockets dieses Tokens werden mit Code `1008` geschlossen.
- Höchstens 100 Tokens. Namen folgen der Regel für Gruppennamen `[a-z0-9][a-z0-9_-]{0,31}`.
- Wo das angezeigte Token landet: Der Befehl schreibt auf die Konsole, nicht ins Log. Logdateien und Journal bekommen es also nicht. Unter systemd hat die Cloud gar keine Konsole, lege Tokens dann in einer `tmux`- oder `screen`-Sitzung an ([Betrieb](operations.de.md)). In Docker ist die Konsole eines angehängten Containers (`docker attach`) seine Standardausgabe, `docker logs` behält das Token also. Leere oder rotiere diese Logs, oder widerrufe das Token und lege ein neues an, wenn die Logs geteilt werden.

Das Token geht als `Authorization: Bearer vct_...` mit. Browser können am WebSocket keine Header setzen. Deshalb nimmt der Event-Stream das Token auch als Subprotokoll `vectora.bearer.<token>` neben `vectora.v1` an. Der Server antwortet nur mit `vectora.v1` und gibt das Token nie zurück. Bietet ein Upgrade Subprotokolle an, aber nicht `vectora.v1`, kommt `400 subprotocol-required`.

### Scopes

| Scope | Erlaubt |
|---|---|
| `services:read` | Dienste auflisten und lesen, Event-Stream |
| `services:write` | starten, stoppen, neu starten, Konsolenbefehl senden |
| `config:read` | `cloud.yml`, Gruppen, Tasks, Server-Schalter, Warnungen und Setup-Status lesen |
| `config:write` | Gruppen und Tasks schreiben und löschen, `wanted` setzen, `cloud.yml` außer `api` und `database` schreiben, neu laden |
| `backups:read` | Backups auflisten |
| `backups:write` | Backup erstellen |
| `node:read` | Node-Status |
| `admin` | alles oben |

Ein Schreib-Scope schließt den Lese-Scope nicht ein. Fehlt der Scope, kommt `403 missing-scope`. Für Änderungen an `api` oder `database` in `cloud.yml` braucht es `admin`. Diese Abschnitte bestimmen, wer den Node erreicht und welches Image `docker compose` mit dem Root-Passwort der Datenbank startet.

**Was die Schreib-Scopes erreichen.** `config:write` ist keine Shell auf dem Node: Die JVM-Flags begrenzt die [Allowlist](config.md#jvm-flag-allowlist), Templates sind Ordner im Cloud-Home, Software und Plugins kommen nur aus den Download-Quellen mit Prüfsumme. `services:write` darf jeden Konsolenbefehl an einen Server schicken, also so viel wie ein Operator an der Serverkonsole. Bieten Plugins Konsolenbefehle für Dateien oder Skripte, reicht das weiter. Diesen Scope bekommen nur Skripte, denen man auch die Serverkonsole geben würde. `GET /api/v1/tokens/self` geht mit jedem gültigen Token und zeigt ID, Name, Scopes und Erstellungszeit.

## Endpunkte

Alle Pfade beginnen mit `/api/v1`. Bodys sind `application/json` in UTF-8, mit denselben Namen (kebab-case) und denselben Records wie die YAML-Dateien ([config.md](config.md)). Die vollständige Tabelle steht in [api.md](api.md#endpoints).

- **Dienste:** `GET /services`, `POST /services` mit `{"task": "lobby"}` (Antwort `201`), `GET /services/{id}`, `POST /services/{id}/stop`, `POST /services/{id}/restart`, `POST /services/{id}/command` mit `{"command": "say hi"}` (Antwort `202`, 1 bis 1024 Zeichen ohne Steuerzeichen).
- **Gruppen und Tasks:** `GET /groups`, `GET|PUT /groups/{name}`, `DELETE /groups/{name}?confirm={name}`, `PUT /groups/{name}/wanted` mit `{"wanted": false}`. Für `/tasks` gilt dasselbe.
- **Nodes:** `GET /nodes`, `GET|PUT /nodes/{name}` (Einträge aus `nodes.yml`, mit `ETag`).
- **Server-Schalter:** `GET /servers`, `PUT /servers/{kind}` mit `{"wanted": true}`.
- **Konfiguration:** `GET|PUT /config` (`cloud.yml`), `POST /config/reload`, `GET /config/warnings`.
- **Node und Setup:** `GET /node`, `GET /setup`.
- **Backups:** `GET /backups`, `POST /backups` mit `{"services": [...], "database": true, "note": "..."}`.
- **Events:** `GET /events` als WebSocket.

Jeder Schreibzugriff läuft über `ConfigService.apply`. Geprüft wird die ganze Konfiguration: Ports, EULA vor `setup-complete`, TLS für eine öffentliche API und die Allowlist der JVM-Flags. Danach wird atomar geschrieben, und die Listener des Nodes sehen die Änderung so, als wäre sie an der Konsole eingegeben worden. Bei einem Fehler wird nichts geschrieben, die Antwort ist `422 invalid-config` mit der Fehlerliste.

**Wanted.** Es gibt zwei Schalter, und eine Gruppe läuft nur, wenn beide an sind: das eigene `wanted` der Gruppe in `groups/<Name>.yml` und der Schalter ihrer Serverart in `cloud.yml` (`servers:`, die Serverart, deren Template-Ordner die Gruppe nutzt). `PUT /groups/{name}/wanted` setzt den Schalter der Gruppe. `PUT /tasks/{name}/wanted` setzt den Schalter der Gruppe des Tasks, denn Tasks haben keinen eigenen. Das betrifft also alle Tasks dieser Gruppe. Eine PandaSpigot-Gruppe mit eigenem Template folgt `servers.legacy-server`. Der 1.8.8-Teil des Netzwerks (PandaSpigot sowie die Templates `Legacy-Proxy` und `Legacy-Server`) braucht zusätzlich `legacy-proxy.enabled`, und das Datenbank-Template läuft nie als Dienst. Nach dieser Regel plant auch der Node. Die Antwort zeigt beide Schalter und `runs`, das die ganze Regel anwendet. `PUT /servers/{kind}` setzt den Schalter der Serverart.

**Löschen.** `DELETE` verlangt den Namen ein zweites Mal als `?confirm=<name>`, sonst kommt `400 confirmation-required`.

**Gleichzeitige Änderungen.** `GET` auf eine Gruppe, einen Task oder `cloud.yml` liefert ein `ETag`. Wer es bei `PUT` oder `DELETE` als `If-Match` mitschickt, bekommt `412 precondition-failed`, falls sich das Dokument inzwischen geändert hat. Geschrieben wird dann nichts. Die Prüfung läuft zusammen mit dem Schreiben: Von mehreren Schreibern mit demselben `ETag` kommt genau einer durch.

**Tasks.** `PUT /tasks/{name}` erwartet den ganzen Task im aktuellen Format (`schema-version: 2`) mit `scaling.static-services` (Pflicht) und `scaling.scale-down-below` (Standard 50); das Beispiel steht in [api.md](api.md). Derselbe Validator wie für die Datei antwortet mit `422` und `static-above-min` (statisch über min) oder `scale-down-not-below-up` (`scale-down-below` nicht kleiner als `scale-up-at`). Ein Body mit `schema-version: 1` (mit `mode`) wird genau wie eine Datei migriert (siehe [Konfiguration](config.md#from-schema-version-1)) und als Version 2 gespeichert; die Antwort zeigt den migrierten Task. Einen Body, den der Leser ablehnt (unbekanntes Feld, falscher Typ, fehlendes Pflichtfeld, ungültiges `mode`), beantwortet die API mit `400` und `errors` mit Feld und Code, etwa `required` oder `invalid-value`. `POST /services` mit einem Task startet den niedrigsten gestoppten statischen Dienst des Tasks, sonst einen neuen statischen, solange der Task weniger als `static-services` hat, sonst einen dynamischen. Siehe [Skalierung](scaling.de.md#statisch-und-dynamisch-in-einem-task).

**Nodes.** `PUT /nodes/{name}` erwartet den ganzen Node wie in `nodes.yml`, zum Beispiel `{"host": "127.0.0.1", "port": 7010, "service-ports": {"first": 30000, "last": 30999}, "max-memory": "8G", "memory-reserve": {"system": "2G"}}`. `id` kann fehlen; ist sie gesetzt, muss sie zum Pfad passen, sonst `400 invalid-value`. `max-memory` (mindestens 512M) und jedes Feld von `memory-reserve` (1M bis 64G; Defaults `system` 1G, `node-overhead` 256M, `database` 512M) sind optional. Ein weggelassenes Feld wird aus der Datei entfernt, dann gilt der Default. Derselbe Validator wie für die Datei antwortet mit `422` (etwa `out-of-range` bei `nodes[0].memory-reserve.system`). `ETag` und `If-Match` funktionieren wie bei Gruppen. Ein `DELETE` gibt es nicht; Nodes entfernt man in der Datei.

**Ausweichgruppe.** `fallback-group` in `PUT /config` nennt die Gruppe, auf die die Proxys einen gekickten Spieler schicken, wenn seine eigene Gruppe keinen freien Server hat, zum Beispiel `"fallback-group": "hub"`. Das Feld ist optional (Standard `lobby`, dann fehlt es in der Antwort); eine Gruppe, die es nicht gibt, beantwortet die API mit `422 invalid-config` und `unknown-group`. Ohne das Feld gilt wieder der Standard. Die Proxys übernehmen den Wert bei ihrem Start (siehe [bridges.md](bridges.md#catching-kicks)).

**Gruppen.** `drain-timeout` ist optional (5s bis 10m, Default 30s), zum Beispiel `"drain-timeout": "45s"`; außerhalb der Grenzen antwortet die API mit `422 out-of-range`.

**Herunterfahren.** Während der Node herunterfährt, beantworten Start, Neustart, Reload und das Erstellen eines Backups die Anfrage mit `503 shutting-down`. Lesen und Stoppen gehen weiter.

**Backups.** `BackupServiceAccess` setzt `BackupAccess` über den `BackupService` aus cloud-backup um. Backups über die API haben den Grund `manual`, wie `backup create` an der Konsole. Läuft schon ein Backup, kommt `409`, bei einem unbekannten Dienst `422`. Antworten enthalten die Backup-ID, nie einen Pfad. Andere Fehler (Dateien, Datenbank-Dump, kaputtes Archiv) beantwortet die API mit `503 unavailable` und nur dem Grund, etwa `(io)`. Die Einzelheiten stehen im Log des Nodes. Solange der Node den Adapter nicht übergibt, antworten beide Endpunkte mit `501 not-available`.

### Fehler

Jeder Fehler hat denselben Body: `code`, `message` und bei Konfigurationsfehlern `errors` mit `file`, `path`, `line`, `code`, `message` und `message-de`. Meldungen nennen Felder und Regeln, nie Werte. Meldungen des Nodes laufen durch dieselbe Schwärzung wie die Konsolenausgabe und werden auf 300 Zeichen gekürzt. Die Liste der Codes steht in [api.md](api.md#errors).

### Strenge Eingaben

- JSON wird nach denselben Regeln gelesen wie die YAML-Dateien. Unbekannte Felder, doppelte Felder und falsche Typen werden abgelehnt (`400`).
- Ein Body hat höchstens 256 KiB (`413`), auch bei Chunked-Encoding. Der Server zählt beim Lesen mit. Schließt eine Antwort die Verbindung, beendet der Server zuerst seine Senderichtung und verwirft bis zu 2 Sekunden lang, was der Client noch schickt. So kommt beim Client die `413` an statt eines Verbindungsabbruchs.
- Bodys brauchen `Content-Type: application/json` (`415`).
- Pfad und Query werden vor dem Routing geprüft: druckbares ASCII, kein `%` im Pfad, keine Segmente `.` oder `..`, IDs `[a-z0-9][a-z0-9_-]{0,63}`, Namen `[a-z0-9][a-z0-9_-]{0,31}`, Query-Schlüssel klein geschrieben und jeder nur einmal.
- HTTP-Pipelining wird nicht unterstützt. Pro Verbindung läuft eine Anfrage nach der anderen.

## Live-Events: `GET /api/v1/events`

Ein WebSocket mit Textframes. Filter sind optional: `?types=service-state,service-output,player` und `?services=lobby-1,survival-1`. Ein unbekannter Typ ergibt `400`.

```json
{"type": "service-output", "time": "2026-10-29T10:00:00Z", "data": {"service": "lobby-1", "line": "Done (3.2s)!"}}
```

- Ausgabezeilen werden geschwärzt, bevor sie den Node verlassen. Jedes Secret aus `vectora.env` wird zu `***`, genau wie in der Konsole.
- Ein langsamer Client bremst den Node nicht. Solange sein Socket nicht beschreibbar ist, werden Events für ihn verworfen und gezählt. Danach kommt ein einzelnes Event `dropped` mit der Anzahl.
- Der Server pingt alle 30 s und schließt einen Stream, von dem 90 s lang nichts kam. Ein Widerruf schließt mit `1008`, das Herunterfahren mit `1001`.
- Ein Handshake aus dem Browser mit einem `Origin`, der nicht in `api.cors-origins` steht, wird mit `403` abgelehnt.
- Höchstens 32 Streams gleichzeitig.

## Transportsicherheit

### Bind und TLS

Standard ist `127.0.0.1`. Jede Adresse außerhalb von Loopback, auch `0.0.0.0`, braucht `api.tls.enabled: true`. Sonst lehnt der Validator die Konfiguration ab (`api-tls-required`), und der Server verweigert zusätzlich den Start.

Mit TLS spricht der Server nur TLS 1.3 und 1.2 und sendet `Strict-Transport-Security`. Zum Keystore (`api.tls.keystore`, PKCS#12, relativ zum Home):

- **Eigenes Zertifikat:** Die PKCS#12-Datei an diesen Pfad legen und das Passwort in `secrets/vectora.env` als `VECTORA_API_KEYSTORE_PASSWORD` eintragen. Fehlt das Passwort, startet der Node die API nicht und nennt den fehlenden Schlüssel.
- **Selbst erzeugt:** Gibt es die Datei nicht, erzeugt der Node mit `keytool` aus dem JDK ein selbstsigniertes Zertifikat. Es nutzt EC P-256, gilt 825 Tage und enthält die Namen `localhost`, `127.0.0.1`, `::1` und die Bind-Adresse, sofern sie keine Wildcard ist. Das Keystore-Passwort ist zufällig und liegt in `secrets/api-keystore.password` (nur für den Besitzer lesbar). `keytool` bekommt es über eine Umgebungsvariable, nie über die Kommandozeile. Bei jedem Start loggt der Node den SHA-256-Fingerprint, damit ein Client ihn pinnen kann.

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

Bei einem Limit kommt `429 rate-limited` mit `Retry-After` in Sekunden.

### Was nie in einer Antwort steht

Passwörter, das Datenbankpasswort, Tokens und ihre Hashes, das Keystore-Passwort und der Inhalt von `vectora.env`. `cloud.yml`, Gruppen und Tasks enthalten grundsätzlich keine Secrets.

### JVM-Flags

Gruppen lassen sich über die API schreiben. Darum gibt es für `process.jvm-flags` eine Allowlist, damit ein gestohlenes `config:write`-Token nicht zur Codeausführung auf dem Node führt. Details: [config.md](config.md#jvm-flag-allowlist).

## Einbindung in cloud-node

`cloud-api` hängt nicht von `cloud-node` ab. Der Node liefert ein `ApiBackend` und startet den Server. Die einzelnen Schritte stehen in [api.md](api.md#wiring-in-cloud-node): Tokens öffnen und den Befehl `api` registrieren, bei `api.enabled` TLS vorbereiten und `ApiServer` starten, Dienst-Events weiterreichen, `beginShutdown()` zu Beginn und `close()` am Ende von `CloudNode.close()`. Das macht `CloudNode` mit `ops.NodeApi`. Eine Ablehnung des Dienstkerns geht nur an den Client, wenn sie einer seiner eigenen kurzen Gründe ist und nichts enthält, das wie ein Pfad aussieht. Alles andere bleibt im Log des Nodes, der Client bekommt einen kurzen Text. `ApiSettings.of` lehnt `api.tls.enabled` ohne Schlüsselmaterial ab, ebenso Schlüsselmaterial bei ausgeschaltetem TLS. `runs` in den Wanted-Endpunkten ist `GroupRules.isWanted` aus cloud-config, also dieselbe Regel, nach der der Node plant.
