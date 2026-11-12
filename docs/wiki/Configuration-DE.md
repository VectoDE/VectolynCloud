# Konfiguration

[English](Configuration)

So wird VectolynCloud konfiguriert. Der Einrichtungsassistent schreibt die erste Konfiguration; danach änderst du sie über Dateien, Konsolenbefehle oder die [API](API-DE). Alle drei laufen durch dieselbe Prüfung: Ein Wert, den die Konsole ablehnt, wird auch in einer Datei und in der API abgelehnt. Die vollständige Referenz mit allen Schlüsseln ist [docs/cloud/config.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/config.md) (Englisch).

## Dateien

Alles liegt im Cloud-Home, dem Ordner, in dem VectolynCloud läuft.

| Datei | Inhalt |
|---|---|
| `config/cloud.yml` | Netzwerkname, Sprache, EULA und Einrichtungsstand, welche Server gewollt sind, Ports von Proxy, Legacy-Proxy, Datenbank und API, Branding |
| `config/nodes.yml` | die Nodes: Host, Kanal-Port, Port-Bereich der Dienste, Speichergrenze und Reserve |
| `config/groups/<name>.yml` | was ein Dienst ausführt: Template, Software (`paper`, `velocity`, `fabric` oder `pandaspigot`), Minecraft-Version, Java, Heap, Plugins und Mods aus dem Katalog, JVM-Flags, Neustartverhalten |
| `config/tasks/<name>.yml` | wie viele Dienste einer Gruppe laufen: statisch, Minimum, Maximum, Spieler je Dienst, Skalierungsschwellen |
| `config/monitor.yml`, `config/backup.yml` | Monitoring und Backups, siehe [Monitoring](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/monitor.md) und [Backups](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/backup.md) (Englisch) |
| `config/cluster.yml` | Head, Mitglieder und widerrufene Zertifikate eines Clusters |
| `config/software.lock.yml` | jedes heruntergeladene Artefakt mit seinem Hash; wird von der Cloud geschrieben, nicht von Hand |
| `secrets/vectolyn.env` | erzeugte Secrets (nur für den Besitzer lesbar, nie in einem Repository) |

Jede Datei beginnt mit `schema-version`. Feldnamen sind kebab-case, Enum-Werte klein geschrieben. Unbekannte Felder, doppelte Felder und falsche Typen sind Fehler mit Datei, Pfad und Zeile.

## Ändern

- **Von Hand.** Eine Datei bearbeiten und `config reload` in der Konsole ausführen. Eine ungültige Datei lässt die aktuelle Konfiguration bestehen und gibt die Fehler aus.
- **Konsole.** `config get|set|list|reload`, `group list|info|create|set|delete|wanted`, `task list|info|create|set|delete|wanted`, `node list|info|set` und `setup` (startet den Assistenten erneut). `help <command>` zeigt die Verwendung.
- **API.** `GET` und `PUT` auf `/api/v1/config`, `/groups`, `/tasks`, `/nodes`, `/servers`, mit `ETag` und `If-Match` gegen gleichzeitige Änderungen.

Die Cloud schreibt eine Datei neu, wenn sie sie ändert, und verliert dabei die Kommentare in dieser Datei; Notizen gehören woanders hin.

## Server auswählen

`servers:` in der `cloud.yml` enthält einen Schalter je Server: proxy, lobby, survival, creative, mod, end, database, minigame und legacy-server. Eine Gruppe läuft nur, wenn ihr eigenes `wanted` und der Schalter ihrer Server-Art beide an sind. Nicht gewollte Server werden nie angelegt, und weder Proxy noch Lobby verweisen auf sie.

## Skalierung

Ein Task bestimmt, wie viele Dienste einer Gruppe laufen. Die ersten `static-services` sind statisch und behalten ihren Ordner; darüber fügt die Cloud dynamische Dienste hinzu, wenn die Last der Gruppe `scale-up-at` überschreitet, und entfernt sie unterhalb von `scale-down-below`, jeweils zwischen `min-services` und `max-services`. Das optionale `scale-up-on` fügt Dienste bei hoher CPU-Last, niedrigen TPS oder vollem Heap hinzu, `schedules` heben das Minimum zu festen Zeiten an. Der Node hält sein Speicherbudget ein. Details: [docs/cloud/scaling.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/scaling.de.md).

## Datenbank

`database.mode` ist `managed` (die Cloud lädt MariaDB herunter und betreibt sie), `docker`, `external` (eine vorhandene MariaDB oder MySQL) oder `h2` (eingebettet). MariaDB hat nur für Windows x64 und Linux x86_64 einen Server-Build, deshalb fällt `managed` auf anderen Systemen auf H2 zurück. Die Connection-Pools werden zentral bemessen. Details: [docs/cloud/database.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/database.md) (Englisch).

## Einrichtung ohne Terminal

Mit `--non-interactive --setup-file setup.yml` liest der Assistent seine Antworten aus einer Datei. Die Datei enthält kein Secret; ein Datenbank-Passwort kommt aus einer Datei oder aus `secrets/vectolyn.env`. Sie gilt nur, solange `setup-complete` auf false steht. Format: [docs/cloud/setup.de.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.de.md).

## Noch nicht verfügbar

Ein JSON Schema für Editoren, Import und Export der gesamten Konfiguration, eine Vorschau einer Änderung vor dem Anwenden, PostgreSQL und der Panel-Editor sind geplant ([#129](https://github.com/VectoDE/VectolynCloud/issues/129), [#151](https://github.com/VectoDE/VectolynCloud/issues/151)). Sie sind noch in keinem Release enthalten.
