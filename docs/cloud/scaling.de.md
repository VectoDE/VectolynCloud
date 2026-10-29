# VectoraCloud-Autoskalierung

Paket `net.vectora.cloud.node.scaling` in `VectoraCloud/cloud-node`. Der Node hält von jedem Task `min-services` Dienste am Laufen, startet dynamische Dienste, wenn die Last einer Gruppe hoch ist, und leert und stoppt sie, wenn sie niedrig ist. Die ausführliche englische Seite ist [scaling.md](scaling.md).

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
  scale-up-at: 75            # Last der Gruppe in Prozent, ab der ein Dienst dazukommt
  scale-down-below: 50       # Last in Prozent, unter der es mit einem Dienst weniger bleiben muss
  scale-down-after: 5m       # so lange muss die niedrige Last anhalten, bevor ein Dienst geht
  cooldown: 60s              # Mindestabstand zwischen zwei Entscheidungen des Tasks
nodes: []
```

### Statisch und dynamisch in einem Task

Es muss `static ≤ min ≤ max` gelten, und `scale-down-below` muss kleiner sein als `scale-up-at`.

- **Statische Dienste** (`static-services`): die ersten des Tasks. Jeder hat einen eigenen Ordner unter dem Template der Gruppe (`runtime`, `runtime-2`, …), der jeden Stopp überdauert, und der Scaler stoppt keinen davon.
- **Dynamische Dienste**: alles über `static-services` bis `max-services`. Jeder läuft in `temp/services/<id>`, und dieser Ordner wird beim Stopp gelöscht.

Ein neuer Dienst ist statisch, solange der Task weniger statische Dienste hat als `static-services`, sonst dynamisch. Die Lobby oben hat also eine statische und bis zu drei dynamische. `static-services = max-services` ist ein rein statischer Task (er skaliert nie), `static-services = 0` ein rein dynamischer.

Wird `static-services` später gesenkt, werden die überzähligen statischen Dienste dynamisch, vom höchsten Index abwärts: Bei drei statischen Lobbys und `static-services: 1` zählen lobby-2 und lobby-3 als dynamisch. Sie werden wie jeder dynamische Dienst herunterskaliert (vorher geleert, siehe unten), behalten beim Stopp aber ihren Ordner, und ein gestoppter zählt nicht mehr zu `min-services`. Wird es erhöht, ist der nächste neue Dienst statisch.

Vorschläge des Setups (statisch/min/max): Proxy und Legacy-Proxy 1/1/1, Survival, Creative, Mod und End 1/1/1, Lobby 1/1/4, Legacy-1.8.8-Server 1/1/3, Minigames 0/1/8; `scale-up-at` 75, `scale-down-below` 50.

Datei, Konsole und API ändern das alle über `ConfigService.apply` und denselben Validator:

| Weg | Beispiel |
|---|---|
| Datei | `tasks/minigame.yml` bearbeiten; der Node liest sie nach 200 ms neu |
| Konsole | `task set minigame max-services 6`, `task set lobby static-services 2`, `task set minigame scale-down-below 40` |
| API | `PUT /api/v1/tasks/minigame` mit dem ganzen Task als JSON (Scope `config:write`) |

Der Scaler liest die Konfiguration in jeder Runde neu. Alle drei Wege wirken deshalb gleich, spätestens nach einer Runde (5 Sekunden). Eine ungültige Änderung wird überall mit denselben Fehlern abgelehnt und ändert nichts.

### Gruppeneinstellungen für den Scaler

`drain-timeout` in `groups/<name>.yml` (5s bis 10m, Standard 30s) gibt an, wie lange ein Dienst der Gruppe Zeit hat, seine Spieler anderswohin zu schicken, bevor der Scaler ihn stoppt. Siehe Regel 4 unten. Ändern lässt er sich in der Datei, mit `group set <Name> drain-timeout 45s`, über `PUT /api/v1/groups/{name}`, mit `drain-timeout:` in der Setup-Datei oder in den erweiterten Einstellungen des Einrichtungsassistenten.

### Node-Einstellungen für den Scaler

In `nodes.yml` kann jeder Node `max-memory` setzen (mindestens 512M, fehlt der Wert, rechnet der Node selbst) und `memory-reserve` mit `system` (Standard 1G), `node-overhead` (Standard 256M) und `database` (Standard 512M, zählt nur, wenn die Cloud die Datenbank im Modus `managed` oder `docker` selbst betreibt), je 1M bis 64G. Die Reserve behält der Node für sich. Das [Speicherbudget](#speicherbudget) nutzt beides. Ändern lässt es sich in der Datei, mit `node set <ID> <Feld> <Wert>`, über `PUT /api/v1/nodes/{name}`, mit `node:` in der Setup-Datei oder in den erweiterten Einstellungen des Einrichtungsassistenten. Siehe [config.md](config.md#nodesyml).

## Welche Tasks skaliert werden

Nur Tasks dieses Nodes (`nodes` leer oder mit diesem Node), deren Gruppe gewollt ist. „Gewollt“ entscheidet die eine Regel `GroupRules.isWanted` aus cloud-config, die auch Planung, Starts, die Proxy-Listen und `runs` in der API bestimmt: das eigene `wanted` der Gruppe, ihr Schalter unter `servers:` in `cloud.yml` und für die 1.8.8-Gruppen `legacy-proxy.enabled` (siehe [node.md](node.md#which-services-run)). Eine abgeschaltete Gruppe wird nie skaliert, ihre Dienste stoppen wie bisher beim Neuladen.

Nichts wird skaliert, solange das Setup nicht abgeschlossen und die EULA nicht akzeptiert ist, während der Befehl `setup` seine Schritte speichert und während der Node herunterfährt.

## Last

Die **Last einer Gruppe** sind die Spieler auf ihren laufenden Diensten geteilt durch deren Plätze. Jeder Dienst hat `players-per-service` seines Tasks als Plätze. Statische und dynamische Dienste zählen gleich, ein Dienst, der gerade geleert wird (unten), zählt nicht. Beispiel: lobby-1 (statisch) mit 10 Spielern und lobby-2 mit 70, je 50 Plätze: 80 von 100, also 80 %.

Unbekannt ist die Last, solange ein laufender Dienst der Gruppe keine bekannte Spielerzahl hat (siehe [Signale](#signale)). Dann wird die Gruppe weder hoch- noch herunterskaliert.

## Regeln

Alle 5 Sekunden prüft der Scaler jeden Task einmal. Nach einer Entscheidung nach Last (Regeln 3 und 4) wartet der Task `cooldown`, bevor die nächste kommt.

1. **min-services.** Zählen weniger Dienste als `min-services`, starten alle fehlenden in derselben Runde, ohne `cooldown`, etwa nachdem die EULA wieder akzeptiert wurde.
   - Ein dynamischer Dienst zählt, solange er vorbereitet wird, startet, läuft oder nach einem Absturz auf seinen Neustart wartet.
   - Ein statischer Dienst innerhalb von `static-services` zählt immer, auch gestoppt oder nach aufgegebenen Neustarts. Ein mit `service stop` angehaltener Server bleibt deshalb aus. Nur ein statischer Dienst, der stoppte, weil seine Gruppe abgeschaltet wurde (oder `eula-accepted` bzw. `setup-complete` zurückgenommen wurde), kommt wieder, sobald der Schalter wieder an ist, mit seinem Index und in seinem Ordner. Ob ein Schalter den Stopp ausgelöst hat, entscheidet die Konfiguration im Moment des Stopps.
   - Ein neuer statischer Platz (neuer Task, höheres `static-services`) bekommt den kleinsten Index der Gruppe, den in diesem Lauf noch kein Dienst hatte. Der Ordner eines von Hand gestoppten Servers bleibt so unberührt.
2. **Über max-services.** Hat ein Task mehr Dienste als `max-services` (das Limit wurde gesenkt), wird ein leerer dynamischer Dienst so entfernt wie in Regel 4: auf den Proxys geschlossen, noch einmal geprüft, dann gestoppt. Ein Dienst mit Spielern wird dafür nie entfernt.
3. **Hochskalieren.** Ein weiterer dynamischer Dienst startet, wenn die Last der Gruppe `scale-up-at` erreicht hat, kein Dienst der Gruppe mehr startet, kein anderer Task der Gruppe in derselben Runde einen gestartet hat, der Task dynamische Dienste haben darf (`static-services < max-services`), `max-services` Platz lässt und das Speicherbudget reicht (unten). Bei 50 Plätzen pro Dienst und 75 %: 75 von 100 Spielern auf zwei Diensten.
4. **Herunterskalieren.** Der Task hat mehr als `min-services` Dienste, und ohne seinen leersten laufenden dynamischen Dienst bliebe der Rest der Gruppe unter `scale-down-below`, die Spieler dieses Dienstes auf den Rest gerechnet. Hält das `scale-down-after` lang ohne Unterbrechung an, geht der Dienst:
   - er wird immer zuerst auf den Proxys **geschlossen**, damit kein neuer Spieler mehr hinkommt;
   - alle Spieler darauf werden auf die anderen Dienste der Gruppe umgezogen (statische eingeschlossen). Die Node liest die Spieler bei jeder Prüfung neu, also wird auch umgezogen, wer zwischendurch dazukam. Wer 2 Sekunden nach seinem Umzugsbefehl noch darauf ist, bekommt ihn noch einmal, dann nach 4 und danach alle 8 Sekunden, falls ein Proxy den ersten verloren oder abgelehnt hat. Jeder Spieler bekommt eine kurze Nachricht in der Sprache seines Clients (Deutsch bei `de`, sonst Englisch);
   - er stoppt erst, wenn er leer ist: Die Proxys führen niemanden mehr darauf **und** seine eigene Bridge meldet 0 Spieler. Hier entscheidet die höhere der beiden Zahlen, damit ein Proxy, der einen Beitritt noch nicht gesehen hat, keinen Server unter einem Spieler stoppen lässt;
   - sind nach der `drain-timeout` der Gruppe (Standard 30 Sekunden) noch Spieler darauf (ein Umzug ging schief, der Proxy ist weg), läuft er weiter und wird wieder geöffnet. Gibt es keinen anderen offenen Server der Gruppe, der die Spieler aufnehmen kann, passiert das sofort, ohne die `drain-timeout` abzuwarten. Niemand wird gekickt. Der Task wartet dann wieder `scale-down-after` bis zum nächsten Versuch;
   - schlägt der Stopp selbst fehl, nachdem er geleert wurde, wird er ebenfalls wieder geöffnet;
   - solange er geleert wird, zählt er weder zu `min-services` noch zur Last, und der Task ist nicht blockiert: Stürzt in der Zeit ein anderer Dienst des Tasks ab, startet sein Ersatz in der nächsten Runde.

   Von gleich leeren Diensten geht der mit dem höchsten Index. Beispiel mit 50 Plätzen und 50 %: 20 und 4 Spieler auf zwei Diensten, ohne den zweiten 24 von 50 (48 %), also wird der zweite geleert. Bei 20 und 5 sind es genau 50 %, beide bleiben.

Weil `scale-down-below` kleiner ist als `scale-up-at`, startet ein gerade entfernter Dienst nicht in der nächsten Runde wieder.

Was nie passiert:

- Ein statischer Dienst innerhalb von `static-services` wird vom Scaler nie gestoppt oder geleert. Spieler eines geleerten dynamischen Dienstes nimmt er aber auf. Nur die überzähligen nach einem gesenkten `static-services` zählen als dynamisch (siehe oben).
- Ein Proxy (`software: velocity`) wird nie hoch- oder herunterskaliert; es gibt einen Proxy pro Node.
- Zum Herunterskalieren wird nie ein Spieler gekickt.
- `min-services: 0` startet nichts. Der erste Dienst eines solchen Tasks kommt über `service start`, danach wird skaliert.

Ein dynamischer Dienst, der mit `service stop` angehalten wird, wird ersetzt, wenn der Task unter `min-services` fällt. Dynamische Dienste sind ein Pool. Wer den dynamischen Teil eines Tasks herunterfahren will, setzt `min-services` auf `static-services`, schaltet seine Gruppe ab oder entfernt den Task.

## Wohin Spieler gehen

Der Proxy schickt einen Spieler auf den Server der Gruppe mit der geringsten Last in Prozent (Spieler / Plätze), bei Gleichstand auf den mit weniger Spielern, dann nach Name. Spieler auf dem Weg zu einem Server zählen dort 10 Sekunden lang mit, damit sich viele Joins auf einmal verteilen. Ein voller Server (Spieler samt denen auf dem Weg dorthin erreichen oder überschreiten seine Plätze) wird übersprungen. Das gilt für:

- den ersten Server beim Join (die `try`-Liste von Velocity bestimmt nur die Gruppe);
- eine Weiterleitung in eine Gruppe, etwa zurück in die Lobby nach einem Kick;
- die Umzüge eines geleerten Servers (der Node plant sie mit derselben Regel, `TransferPlanner`);
- einen gekickten Spieler, ob sein Server gestoppt wurde, abgestürzt ist, die Verbindung verloren hat, voll ist oder ihn mit eigenen Worten gekickt hat: ein anderer Server derselben Gruppe, sonst die Lobby, sonst wird der Spieler mit dem ursprünglichen Grund getrennt. Nach einem Kick mit eigenen Worten bekommt der Spieler diesen Grund nach dem Umzug als Chatzeile. Bans, die Whitelist und Kicks, die die Moderation von VectoraCloud markiert, fängt der Proxy nie ab. Jeder Server wird pro Vorfall nur einmal versucht, und pro Spieler werden höchstens 3 Kicks in 60 Sekunden abgefangen. Eine Weiterleitung in eine Gruppe nach einem Kick zählt auf diese Grenze und überspringt die schon versuchten Server, damit ein Spieler nie zwischen zwei Lobbys hin- und herspringt (Einzelheiten in [bridges.md](bridges.md#catching-kicks)).

Hauptproxy und Legacy-Proxy arbeiten gleich. Der Node teilt den Proxys mit `ServerCapacity` die Plätze jedes Backends mit und ob es offen ist (siehe [protocol.md](protocol.md)); ein Backend, das geleert wird, ist geschlossen. Die Proxys lehnen dann jeden Wechsel dorthin ab, auch mit `/server`, außer für Spieler mit der Berechtigung `vectora.server.bypass-closed`; nur der erste Server eines Logins wird durchgelassen (siehe [bridges.md](bridges.md#closed-servers)). Ein Backend ohne Plätze (von einem älteren Node) wählt der Proxy so, wie Velocity es täte.

## Signale

| Was | Quelle |
|---|---|
| Spieler pro Dienst | Der neueste Messwert des [Monitors](monitor.md): `ServiceStatus.playersOnline`, das die Bridge des Dienstes alle 5 Sekunden meldet, höchstens ihr eigenes `maxPlayers`. Ein Messwert älter als `stale-after` (60 s) zählt nicht. Solange ein Proxy verbunden ist, der den Dienst führt, gilt der kleinere Wert aus Bridge und Proxy (`PlayerJoin`, `PlayerSwitch`, siehe [bridges.md](bridges.md#player-events)). Ein manipulierter Server, der mehr Spieler meldet, als der Proxy auf ihm sieht, kann so keine Server starten. Ohne einen solchen Proxy gilt der Wert der Bridge. |
| Speicher des Nodes | Der neueste Node-Messwert des Monitors (`node-memory`: belegter und gesamter Speicher der Maschine bzw. des Containers, wenn Java ein Limit sieht). |

Eine unbekannte Zahl (noch kein Bericht, ein veralteter, ein Server ohne Bridge) gilt nie als voll und nie als leer. Sie macht die Last ihrer Gruppe unbekannt und blockiert damit Hoch- und Herunterskalieren der Gruppe. Die Node schreibt das je Dienst einmal als Warnung ins Log (`Scaling: group … is not scaled while … reports no player count`) und erst wieder, nachdem die Zahl zwischendurch bekannt war. Mit `enabled: false` in `monitor.yml` gibt es keine Messwerte. Der Scaler hält dann `min-services` und skaliert weder hoch noch herunter.

## Speicherbudget

Hochskalieren (Regel 3) braucht außerdem:

- Die maximalen Heaps (`heap.max`) aller Dienste des Nodes, die einen Prozess haben oder gleich bekommen, plus der neue passen in das **Budget** des Nodes.
- Mindestens `heap.max` des neuen Dienstes ist gerade frei.

Das Budget ist `max-memory` des Nodes in `nodes.yml`, höchstens der gemessene Speicher der Maschine (bzw. des Containers). Ohne `max-memory` hält der Node eine Reserve frei und nutzt den Rest. Die Größen stammen aus `memory-reserve` des Nodes in `nodes.yml`; ein fehlendes Feld (oder ein Node ohne Eintrag) hat den Standardwert:

| Reserve | Feld | Standard |
|---|---|---|
| Betriebssystem | `system` | 1 GiB |
| Die JVM des Nodes selbst | `node-overhead`, dazu ihr maximaler Heap (`-Xmx` oder was die JVM gewählt hat) | 256 MiB für Metaspace, Threads und Puffer |
| MariaDB, die die Cloud betreibt | `database`, nur wenn `servers.database` an ist und `database.mode` `managed` oder `docker` | 512 MiB |

Beispiel mit den Standardwerten: Maschine mit 16 GiB, Node mit `-Xmx1G`, verwaltete Datenbank: 16 - 1 - 1 - 0,25 - 0,5 = 13,25 GiB für die Heaps der Dienste. Eine Datenbank, die der Betreiber woanders betreibt (`external`), braucht hier keine Reserve. Teilen sich andere Programme die Maschine, `max-memory` setzen.

Fehlt eins davon oder ist der Speicher des Nodes noch nicht gemessen, wartet der Task. Das Log nennt den Grund einmal, bis er sich ändert:

```text
Scaling: task minigame needs another service (1G heap), but the maximum heaps of the services on this node would exceed its memory
```

`min-services` hält das Budget nicht auf, genau wie beim Start des Nodes: Wer das Minimum setzt, verlangt diese Server. `service start` von Hand wird ebenfalls nicht geprüft.

## Fehlgeschlagene Starts

Ein Start, der scheitert (Download, Template, Port), oder ein Absturz eines Dienstes des Tasks zählt als Fehlschlag. Nach 3 in Folge, ohne dass dazwischen ein Dienst des Tasks 5 Minuten lief, startet der Scaler für diesen Task 10 Minuten lang nichts und schreibt das ins Log. Eine Änderung der Task- oder Gruppendatei beendet die Pause sofort. Der Neustart nach einem Absturz (`process.restart` der Gruppe) ist davon nicht betroffen, er hat sein eigenes Limit.

## Log

Jeder Start, Stopp und Umzug steht mit Grund im Log:

```text
Scaling: minigame-3 of task minigame is started (the load of the group has reached 75 %)
Scaling: minigame-3 of task minigame is stopped (with one service fewer the load stays below 50 %, for 5m)
Scaling: the players of minigame-2 of task minigame are moved to other servers (with one service fewer the load stays below 50 %, for 5m)
Scaling: minigame-2 keeps running: players are still on it after 30 s, a move did not work
Scaling: minigame-2 keeps running: no other open server of its group can take its players
Scaling: minigame-2 keeps running: moving its players was interrupted
```

Die letzten drei Zeilen nennen, warum ein Dienst nicht geleert wurde: Zeit abgelaufen, kein anderer offener Server der Gruppe, oder das Warten wurde unterbrochen (die Node fährt herunter).

## IDs, Ordner und Proxys

Ein skalierter Dienst ist ein gewöhnlicher Dienst des Service-Kerns ([node.md](node.md#ids-folders-and-ports)): ID `<gruppe>-<index>` mit dem kleinsten freien Index, dynamische Ordner unter `temp/services/<id>`, ein Port aus den `service-ports` des Nodes. Die Proxys erfahren per `RegisterServer` von ihm, sobald er läuft, und per `UnregisterServer`, wenn er stoppt.

## Grenzen

- Ein Node. Mit dem Cluster (PR 20) skaliert jeder Node seine eigenen Tasks; ein Task auf mehreren Nodes wird noch nicht zwischen ihnen verteilt.
- Die Proxys kennen die Plätze nur von Backends, die der Node angemeldet hat. Von Hand in `velocity.toml` eingetragene Server wählt der Proxy so, wie Velocity es täte.
- Skaliert wird ab einem laufenden Dienst: Ein Task mit `min-services: 0` ohne Dienst hat keine Spielerzahl, nach der er skalieren könnte (noch keine Warteschlange).
- Das Budget rechnet mit den maximalen Heaps, nicht mit dem, was eine JVM darüber hinaus braucht (Metaspace, Threads, nativer Speicher). In `heap.max` Luft lassen.
