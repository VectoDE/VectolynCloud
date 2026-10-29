# VectoraCloud-Autoskalierung

Paket `net.vectora.cloud.node.scaling` in `VectoraCloud/cloud-node`. Der Node hält von jedem Task `min-services` Dienste am Laufen und startet oder stoppt dynamische Dienste nach Spielerzahl. Die ausführliche englische Seite ist [scaling.md](scaling.md).

## Konfiguration

Skaliert wird pro Task in `tasks/<name>.yml` (siehe [config.md](config.md#tasksnameyml)):

```yaml
schema-version: 2
group: lobby
scaling:
  static-services: 1         # laufen immer, behalten ihren Ordner, der Scaler stoppt sie nie
  min-services: 1            # laufen immer, statische eingerechnet
  max-services: 4            # nie mehr, auch nicht von Hand
  players-per-service: 50
  scale-up-at: 75            # Prozent von players-per-service
  scale-down-below: 50       # Prozent; gespeichert und geprüft, genutzt ab einer späteren Änderung
  scale-down-after: 5m       # so lange leer, bevor ein überzähliger Dienst stoppt
  cooldown: 60s              # Mindestabstand zwischen zwei Entscheidungen des Tasks
nodes: []
```

### Statisch und dynamisch in einem Task

Es muss `static ≤ min ≤ max` gelten, und `scale-down-below` muss kleiner sein als `scale-up-at`.

- **Statische Dienste** (`static-services`): die ersten des Tasks. Jeder hat einen eigenen Ordner unter dem Template der Gruppe (`runtime`, `runtime-2`, …), der jeden Stopp überdauert, und der Scaler stoppt keinen davon.
- **Dynamische Dienste**: alles über `static-services` bis `max-services`. Jeder läuft in `temp/services/<id>`, und dieser Ordner wird beim Stopp gelöscht.

Ein neuer Dienst ist statisch, solange der Task weniger statische Dienste hat als `static-services`, sonst dynamisch. Die Lobby oben hat also eine statische und bis zu drei dynamische. `static-services = max-services` ist ein rein statischer Task (er skaliert nie), `static-services = 0` ein rein dynamischer.

Wird `static-services` später gesenkt, werden vorhandene statische Dienste nicht dynamisch: Sie behalten ihren Ordner und zählen weiter. Wird es erhöht, ist der nächste neue Dienst statisch.

Vorschläge des Setups (statisch/min/max): Proxy und Legacy-Proxy 1/1/1, Survival, Creative, Mod und End 1/1/1, Lobby 1/1/4, Legacy-1.8.8-Server 1/1/3, Minigames 0/1/8; `scale-up-at` 75, `scale-down-below` 50.

Die Lastregeln mit `scale-down-below`, die Verteilung auf Nodes und das Umziehen von Spielern folgen in einer zweiten Änderung. Bis dahin gelten die Regeln unten unverändert.

Datei, Konsole und API ändern das alle über `ConfigService.apply` und denselben Validator:

| Weg | Beispiel |
|---|---|
| Datei | `tasks/minigame.yml` bearbeiten; der Node liest sie nach 200 ms neu |
| Konsole | `task set minigame max-services 6`, `task set lobby static-services 2`, `task set minigame scale-down-below 40` |
| API | `PUT /api/v1/tasks/minigame` mit dem ganzen Task als JSON (Scope `config:write`) |

Der Scaler liest die Konfiguration in jeder Runde neu. Alle drei Wege wirken deshalb gleich, spätestens nach einer Runde (5 Sekunden). Eine ungültige Änderung wird überall mit denselben Fehlern abgelehnt und ändert nichts.

### Gruppeneinstellungen für den Scaler

`drain-timeout` in `groups/<name>.yml` (5s bis 10m, Standard 30s) gibt an, wie lange ein Dienst der Gruppe Zeit hat, seine Spieler anderswohin zu schicken, bevor der Scaler ihn stoppt. Gespeichert und geprüft wird der Wert schon jetzt, genutzt ab einer späteren Änderung. Ändern lässt er sich in der Datei, mit `group set <Name> drain-timeout 45s`, über `PUT /api/v1/groups/{name}` oder mit `drain-timeout:` in der Setup-Datei.

## Welche Tasks skaliert werden

Nur Tasks dieses Nodes (`nodes` leer oder mit diesem Node), deren Gruppe gewollt ist. „Gewollt“ entscheidet die eine Regel `GroupRules.isWanted` aus cloud-config, die auch Planung, Starts, die Proxy-Listen und `runs` in der API bestimmt: das eigene `wanted` der Gruppe, ihr Schalter unter `servers:` in `cloud.yml` und für die 1.8.8-Gruppen `legacy-proxy.enabled` (siehe [node.md](node.md#which-services-run)). Eine abgeschaltete Gruppe wird nie skaliert, ihre Dienste stoppen wie bisher beim Neuladen.

Nichts wird skaliert, solange das Setup nicht abgeschlossen und die EULA nicht akzeptiert ist, während der Befehl `setup` seine Schritte speichert und während der Node herunterfährt.

## Regeln

Alle 5 Sekunden prüft der Scaler jeden Task einmal und trifft höchstens eine Entscheidung. Nach einem Start oder Stopp wartet der Task `cooldown`, bevor die nächste kommt.

1. **min-services.** Zählen weniger Dienste als `min-services`, startet einer mehr.
   - Ein dynamischer Dienst zählt, solange er vorbereitet wird, startet, läuft oder nach einem Absturz auf seinen Neustart wartet.
   - Ein statischer Dienst zählt immer, auch gestoppt oder nach aufgegebenen Neustarts. Ein mit `service stop` angehaltener Server bleibt deshalb aus. Nur ein statischer Dienst, der stoppte, weil seine Gruppe abgeschaltet wurde (oder `eula-accepted` bzw. `setup-complete` zurückgenommen wurde), kommt wieder, sobald der Schalter wieder an ist, mit seinem Index und in seinem Ordner.
   - Ein neuer statischer Platz (neuer Task, höheres `static-services`) bekommt den kleinsten Index der Gruppe, den in diesem Lauf noch kein Dienst hatte. Der Ordner eines von Hand gestoppten Servers bleibt so unberührt.
2. **Über max-services.** Hat ein Task mehr Dienste als `max-services` (das Limit wurde gesenkt), stoppt ein leerer dynamischer Dienst, ohne `scale-down-after` abzuwarten. Ein Dienst mit Spielern wird nie gestoppt.
3. **Hochskalieren.** Ein weiterer dynamischer Dienst startet, wenn jeder laufende Dienst des Tasks `scale-up-at` Prozent von `players-per-service` erreicht hat, keiner mehr startet, `max-services` Platz lässt und das Speicherbudget reicht (unten). Bei 50 Spielern pro Dienst und 80 % sind das 40 Spieler auf jedem.
4. **Herunterskalieren.** Ein dynamischer Dienst stoppt, wenn er `scale-down-after` lang keine Spieler hatte, der Task mehr als `min-services` Dienste hat und ein anderer Dienst des Tasks noch Platz unter der Schwelle hat oder gerade startet. Ohne diese letzte Bedingung würde der Stopp sofort einen neuen Start auslösen (Hysterese). Von mehreren Kandidaten geht der mit dem höchsten Index.

Was nie passiert:

- Ein statischer Dienst wird vom Scaler nie gestoppt, auch nicht, nachdem `static-services` gesenkt wurde.
- Ein Proxy (`software: velocity`) wird nie hoch- oder herunterskaliert; es gibt einen Proxy pro Node.
- Ein Dienst mit Spielern wird nie gestoppt. Spieler werden vorher nicht umgezogen, der Scaler wartet, bis der Server leer ist. Zum Umziehen fehlt dem Cloud-Kanal noch eine Nachricht.
- `min-services: 0` startet nichts. Der erste Dienst eines solchen Tasks kommt über `service start`, danach wird skaliert.

Ein dynamischer Dienst, der mit `service stop` angehalten wird, wird ersetzt, wenn der Task unter `min-services` fällt. Dynamische Dienste sind ein Pool. Wer den dynamischen Teil eines Tasks herunterfahren will, setzt `min-services` auf `static-services`, schaltet seine Gruppe ab oder entfernt den Task.

## Signale

| Was | Quelle |
|---|---|
| Spieler pro Dienst | Der neueste Messwert des [Monitors](monitor.md): `ServiceStatus.playersOnline`, das die Bridge des Dienstes alle 5 Sekunden meldet. Ein Messwert älter als `stale-after` (60 s) zählt nicht. Dazu die Spieler, die die Proxys auf diesem Dienst melden (`PlayerJoin`, `PlayerSwitch`, siehe [bridges.md](bridges.md#player-events)). Es gilt der höhere Wert. |
| Speicher des Nodes | Der neueste Node-Messwert des Monitors (`node-memory`: belegter und gesamter Speicher der Maschine bzw. des Containers, wenn Java ein Limit sieht). |

Eine unbekannte Zahl (noch kein Bericht, ein veralteter, ein Server ohne Bridge) gilt nie als voll und nie als leer. Ein solcher Dienst blockiert das Hochskalieren seines Tasks und wird nicht gestoppt. Mit `enabled: false` in `monitor.yml` gibt es keine Messwerte. Der Scaler hält dann `min-services` und skaliert weder hoch noch herunter.

## Speicherbudget

Hochskalieren (Regel 3) braucht außerdem:

- Die maximalen Heaps (`heap.max`) aller Dienste des Nodes, die einen Prozess haben oder gleich bekommen, plus der neue passen in den Speicher des Nodes.
- Mindestens `heap.max` des neuen Dienstes ist gerade frei.

Fehlt eins davon oder ist der Speicher des Nodes noch nicht gemessen, wartet der Task. Das Log nennt den Grund einmal, bis er sich ändert:

```text
Scaling: task minigame needs another service (1G heap), but the maximum heaps of the services on this node would exceed its memory
```

`min-services` hält das Budget nicht auf, genau wie beim Start des Nodes: Wer das Minimum setzt, verlangt diese Server. `service start` von Hand wird ebenfalls nicht geprüft.

## Fehlgeschlagene Starts

Ein Start, der scheitert (Download, Template, Port), oder ein Absturz eines Dienstes des Tasks zählt als Fehlschlag. Nach 3 in Folge, ohne dass dazwischen ein Dienst des Tasks 5 Minuten lief, startet der Scaler für diesen Task 10 Minuten lang nichts und schreibt das ins Log. Eine Änderung der Task- oder Gruppendatei beendet die Pause sofort. Der Neustart nach einem Absturz (`process.restart` der Gruppe) ist davon nicht betroffen, er hat sein eigenes Limit.

## Log

Jeder Start und Stopp steht mit Grund im Log:

```text
Scaling: minigame-3 of task minigame is started (every running service has reached 80 % of 50 players)
Scaling: minigame-3 of task minigame is stopped (empty for 5m, another service has room)
```

## IDs, Ordner und Proxys

Ein skalierter Dienst ist ein gewöhnlicher Dienst des Service-Kerns ([node.md](node.md#ids-folders-and-ports)): ID `<gruppe>-<index>` mit dem kleinsten freien Index, dynamische Ordner unter `temp/services/<id>`, ein Port aus den `service-ports` des Nodes. Die Proxys erfahren per `RegisterServer` von ihm, sobald er läuft, und per `UnregisterServer`, wenn er stoppt.

## Grenzen

- Ein Node. Mit dem Cluster (PR 20) skaliert jeder Node seine eigenen Tasks; ein Task auf mehreren Nodes wird noch nicht zwischen ihnen verteilt.
- Spieler werden vor dem Herunterskalieren nicht umgezogen; der Scaler wartet, bis der Server leer ist.
- Skaliert wird ab einem laufenden Dienst: Ein Task mit `min-services: 0` ohne Dienst hat keine Spielerzahl, nach der er skalieren könnte (noch keine Warteschlange).
- Das Budget rechnet mit den maximalen Heaps, nicht mit dem, was eine JVM darüber hinaus braucht (Metaspace, Threads, nativer Speicher). In `heap.max` Luft lassen.
