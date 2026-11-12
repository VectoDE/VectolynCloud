# API

[English](API)

VectolynCloud hat eine JSON-API über HTTP mit einem WebSocket für Live-Ereignisse. Sie ist für Skripte und für das Dashboard gedacht, das für Etappe 8 geplant ist. Die vollständige Referenz ist [docs/cloud/api.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/api.de.md); die maschinenlesbare Beschreibung ist [api.openapi.yaml](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/api.openapi.yaml).

## Einschalten

Die API ist standardmäßig aus. Einstellung in `config/cloud.yml`:

```yaml
api:
  enabled: true
  bind: 127.0.0.1
  port: 8085
```

Änderungen an `api:` gelten ab dem nächsten Start. Jede andere Bind-Adresse als Loopback braucht `api.tls.enabled: true`; sonst lehnt die Cloud die Konfiguration ab. Mit TLS spricht sie TLS 1.3 und 1.2 und sendet `Strict-Transport-Security`. Zugriff von anderen Ursprüngen (CORS) ist aus, bis du Ursprünge in `api.cors-origins` einträgst.

## Tokens und Scopes

Jede Anfrage braucht ein Token; es gibt keinen anonymen Endpunkt. Ein Token legst du in der Cloud-Konsole an:

```text
api token create dashboard services:read,config:read,node:read
```

Das Token beginnt mit `vct_`, wird einmal angezeigt und nur als SHA-256-Hash gespeichert. Gesendet wird es als `Authorization: Bearer vct_...`. `api token list` und `api token revoke <name>` verwalten Tokens; ein Widerruf wirkt sofort. Ein drittes Argument gibt dem Token eine Laufzeit (`api token create ci services:read 90d`, 1h bis 3650d); danach scheitert es wie ein widerrufenes. Ein Token mit `admin` kann dasselbe über `/api/v1/admin/tokens`.

| Scope | Erlaubt |
|---|---|
| `services:read` | Dienste auflisten und lesen; Dienst- und Spieler-Ereignisse |
| `services:write` | starten, stoppen, neu starten, einen Konsolenbefehl senden |
| `config:read` | Konfiguration lesen, Einrichtungsstand |
| `config:write` | Gruppen, Tasks, Server-Schalter und `cloud.yml` ändern (nicht `api` und `database`), neu laden |
| `backups:read`, `backups:write` | Backups auflisten, ein Backup anlegen |
| `node:read` | Node-Status; Metrik-Ereignisse |
| `admin` | alles, einschließlich der Abschnitte `api` und `database` und der Token-Verwaltung |

## Endpunkte

Alle Pfade beginnen mit `/api/v1`.

| Pfad | Was er tut |
|---|---|
| `GET /services`, `POST /services`, `GET /services/{id}` | auflisten, einen weiteren Dienst eines Tasks starten, einen lesen |
| `POST /services/{id}/stop`, `/restart`, `/command` | stoppen, neu starten, eine Zeile in die Konsole eingeben |
| `GET/PUT/DELETE /groups/{name}`, `PUT /groups/{name}/wanted` | Gruppen |
| `GET/PUT/DELETE /tasks/{name}`, `PUT /tasks/{name}/wanted` | Tasks |
| `GET /nodes`, `GET/PUT /nodes/{name}` | Nodes |
| `GET/PUT /servers`, `PUT /servers/{kind}` | welche Server gewollt sind |
| `GET/PUT /config`, `POST /config/reload`, `GET /config/warnings` | `cloud.yml` und Neuladen |
| `GET /node`, `GET /setup` | Node-Status, Einrichtungsstand |
| `GET/POST /backups` | Backups auflisten und anlegen |
| `GET /events` (WebSocket) | Live-Ereignisse: Dienstzustand, Dienstausgabe, Spieler, Metriken (`node:read`), Node-Log (`admin`) |

Schreibzugriffe werden als Ganzes geprüft, bevor etwas gespeichert wird. `DELETE` braucht `?confirm=<name>`. `GET` liefert einen `ETag`; schickst du ihn als `If-Match` zurück, überschreibst du keine fremde Änderung (sonst `412`). Fehler haben eine einheitliche Form mit `code`, Meldung und, bei Konfigurationsproblemen, Datei, Pfad und Zeile.

## Grenzen

Bodys sind höchstens 256 KiB groß, JSON wird strikt gelesen, und es gibt Ratenbegrenzungen je Client-Adresse und je Token (Standard 600 Anfragen pro Minute je Adresse und 300 je Token). Jede Antwort zeigt das Budget des Tokens in `X-RateLimit-Limit` und `X-RateLimit-Remaining`. Listen lassen sich mit `?limit=50` seitenweise abrufen; der `Link`-Header zeigt auf die nächste Seite, `X-Total-Count` nennt die Größe der ganzen Liste. Höchstens 32 Ereignisströme laufen gleichzeitig. Langsame Stream-Clients verlieren Ereignisse, die dann in einem einzigen `dropped`-Ereignis gemeldet werden; sie bremsen den Node nie aus.

## Beispiel

```sh
curl -s -H "Authorization: Bearer $VECTOLYN_TOKEN" http://127.0.0.1:8085/api/v1/services
```

## Noch nicht verfügbar

gRPC, GraphQL und das Web-Panel sind geplant ([#154](https://github.com/VectoDE/VectolynCloud/issues/154), [#155](https://github.com/VectoDE/VectolynCloud/issues/155), [#151](https://github.com/VectoDE/VectolynCloud/issues/151)).
