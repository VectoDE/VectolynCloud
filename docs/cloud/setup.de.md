# Einrichtungsassistent

English: [setup.md](setup.md)

VectoraCloud hat einen Startpunkt: `java -jar VectoraCloud.jar`. Solange in `cloud.yml` `setup-complete: false` steht, läuft die Einrichtung, bevor der Node einen Server startet. Es gibt drei Wege, und alle schreiben über denselben `ConfigService` wie die Konsolenbefehle und Änderungen von Hand:

| Weg | Wann | Wie |
|---|---|---|
| Fragen in der Konsole | erster Start mit Terminal, später der Befehl `setup` | `SetupWizard.run` |
| Setup-Datei | Docker, Pterodactyl, CI, jeder Start ohne jemanden an der Tastatur | `--setup-file setup.yml` |
| Dateien und Befehle | jederzeit danach | `cloud.yml`, `groups/`, `tasks/` bearbeiten oder `config set`, `group`, `task` (siehe [Konsole](console.de.md)) |

Das Dashboard kommt später als vierter Weg dazu und nutzt dieselbe Schicht. Der Code liegt in `VectoraCloud/cloud-setup` (`net.vectora.cloud.setup`).

## Die Fragen

Reihenfolge wie unten. Jede Frage zeigt ihren Vorschlag in eckigen Klammern, Enter übernimmt ihn. `!cancel` oder Strg+C bricht jederzeit ab, dann wird nichts geschrieben.

1. **Sprache** (`de` oder `en`). Die Antwort gilt sofort für die folgenden Fragen.
2. **Minecraft-EULA** mit Link <https://aka.ms/MinecraftEULA>. Ohne Zustimmung endet die Einrichtung, und die Cloud startet keinen Server. Ist sie einmal erteilt, fällt die Frage bei späteren Läufen weg.
3. **Netzwerkname**, 1 bis 32 Buchstaben, Ziffern, Leerzeichen, `.`, `_` oder `-`.
4. **Server.** Ein Ja/Nein-Schalter je Server: Proxy, Lobby, Survival, Creative, Mod-Server (Fabric), End, Minigames und Legacy-Proxy (Velocity für 1.8.8, eigener Port, standardmäßig aus). Ist der Legacy-Proxy an, kommt die Frage nach dem 1.8.8-Server (PandaSpigot). Wer den Proxy abschaltet, bekommt eine Warnung, weil dann niemand von außen joinen kann. Ab zwei Minigame-Gruppen hat jede Gruppe einen eigenen Schalter.
5. **Netzwerk.** Bind-Adresse (`0.0.0.0` heißt alle Adressen). Mit Proxy: Java-Port und Bedrock-Port (UDP). Mit Legacy-Proxy: dessen Port. Ports, die der Kanal des Nodes oder der Dienst-Bereich schon belegt, werden direkt bei der Eingabe abgelehnt. Auf Pterodactyl ist `SERVER_PORT` der Vorschlag für den Java-Port, und der Assistent erinnert daran, dass jeder Port eine Allocation im Panel braucht.
6. **Speicher.** Maximaler Heap je gewollter Gruppe, mit diesen Vorschlägen:

   | Gruppe | Vorschlag |
   |---|---|
   | proxy, legacy-proxy | 512M |
   | lobby, end, legacy-server | 1G |
   | minigame | 1536M |
   | creative | 2G |
   | survival, mod | 3G |

   Ab 256M, in `M` oder `G`. Der Mindest-Heap wird 512M oder das Maximum, je nachdem, was kleiner ist. Der Assistent zählt alles zusammen und warnt, wenn die Summe den Speicher der Maschine oder des Pterodactyl-Servers (`SERVER_MEMORY`) übersteigt.
7. **Dienste.** Für jeden gewollten Server außer den Proxys die Zahl der Dienste als `statisch/min/max`, mit diesen Vorschlägen:

   | Server | Vorschlag |
   |---|---|
   | lobby | 1/1/4 |
   | legacy-server (1.8.8) | 1/1/3 |
   | minigame | 0/1/8 |
   | survival, creative, mod, end | 1/1/1 |

   Statische Dienste behalten ihre Welten und laufen immer. Darüber startet die Cloud nach Spielerzahl dynamische Dienste, frisch aus dem Template, bis max (siehe [Skalierung](scaling.de.md#statisch-und-dynamisch-in-einem-task)). `statisch ≤ min ≤ max` und max von 1 bis 1000 werden direkt bei der Eingabe geprüft. Die Proxys bekommen immer 1/1/1. Neue Tasks bekommen `scale-up-at` 75 und `scale-down-below` 50. Bei einem vorhandenen Task werden nur die drei Zahlen geschrieben, und nur wenn sie sich ändern.
8. **Erweiterte Einstellungen** (optional). Eine Ja/Nein-Frage, Vorschlag Nein; bei Nein kommt nichts davon. Bei Ja: `drain-timeout` jeder gewollten Gruppe (5s bis 10m, ungesetzt als 30s angezeigt), danach für diesen Node `max-memory` (mindestens 512M, `auto` lässt den Node selbst rechnen) und `memory-reserve.system`, `.node-overhead`, `.database` (je 1M bis 64G; Standard 1G, 256M, 512M). Enter übernimmt den Wert in Klammern, `default` entfernt einen gesetzten Wert, dann gilt wieder der Standard. Jede Antwort wird direkt bei der Eingabe mit denselben Grenzen geprüft wie im [Validator](config.md#validation-rules). Siehe [Skalierung](scaling.de.md#node-einstellungen-für-den-scaler).
9. **Datenbank.** `managed` (die Cloud betreibt ein eigenes MariaDB), `docker`, `external` oder `h2`. Auf Pterodactyl fehlt `docker`, weil der Server dort schon in einem Container ohne Zugriff auf Docker läuft. Wo es kein MariaDB-Server-Build gibt (alles außer Windows x64 und Linux x86_64 mit glibc), steht bei `managed`, dass dort H2 genutzt wird, und H2 ist der Vorschlag. Nach `managed` erklärt der Assistent den Schalter dahinter: `servers.database: true` in `cloud.yml` betreibt MariaDB, `false` nutzt H2-Dateien pro Server. Bei `external` fragt er Host, Port (3306), Datenbankname, Benutzer und Passwort ab.

Danach kommt eine Zusammenfassung: alle Antworten, die erzeugten Secrets (als „verborgen“) und die Dateien, die geschrieben würden. Zur Wahl stehen `save`, `change` (alle Fragen noch einmal, mit deinen Antworten als Vorschlag) und `cancel`. Geschrieben wird nur bei `save`. Findet die Prüfung ein Problem, gibt es `save` erst, wenn die Antwort geändert ist.

Nach dem Speichern sagt der Assistent, wer `secrets/vectora.env` lesen kann (siehe [unten](#wer-die-secrets-lesen-kann)).

### Das Passwort einer externen Datenbank

Secrets erscheinen nie auf dem Bildschirm und landen nie in der History der Konsole.

- **Die Konsole verbirgt Eingaben** (JLine in einem echten Terminal): Das Passwort wird maskiert abgefragt. Bei einem späteren Lauf behält Enter das gespeicherte.
- **Die Konsole zeigt Eingaben** (einfaches stdin, etwa manche IDEs oder `docker attach` ohne TTY): Der Assistent weist darauf hin und bietet drei Wege an:
  - `file`: Du trägst `VECTORA_DB_PASSWORD=...` selbst in `secrets/vectora.env` ein (der volle Pfad wird angezeigt) und bestätigst. Der Assistent prüft die Datei und fragt erneut, bis er einen Wert findet.
  - `type`: Du tippst es trotzdem ein und weißt, dass es sichtbar ist.
  - `keep`: Das gespeicherte Passwort bleibt (nur, wenn es eins gibt).

Alle anderen Secrets (Forwarding-Secrets, Passwörter der verwalteten Datenbank, Management-Secret, Floodgate-Schlüssel) entstehen mit `SecureRandom` und werden nie angezeigt. Siehe [config](config.md#secrets).

### Erneut ausführen: `setup`

Der Konsolenbefehl `setup` stellt die Fragen noch einmal. Die aktuelle Konfiguration liefert die Vorschläge, geschrieben wird nur, was du änderst. Änderst du nichts, sagt der Assistent das und schreibt nichts. Änderst du nur den Heap der Lobby, wird nur `groups/lobby.yml` geschrieben. Der Assistent liest die Dateien beim Start; während du antwortest, kann jemand sie über die API oder die Konsole ändern. Beim Heap, bei `static/min/max` jeder Task (jede Zahl für sich), `drain-timeout`, `max-memory` und jedem Feld von `memory-reserve` gilt: Ein Wert, den du so lässt, wie er angezeigt wurde, übernimmt beim Speichern den Stand der Datei. Eine solche Änderung geht also nicht verloren. Ein Wert, den du änderst, wird geschrieben, und `default` oder `auto` entfernt die Einstellung auch dann, wenn sie zwischendurch geändert wurde. Eine erteilte EULA-Zustimmung und `setup-complete` bleiben. Ein ausgeschalteter Server stoppt sofort (siehe [node](node.md#reload)); ein eingeschalteter startet mit `service start <Task>` oder beim nächsten Start der Cloud. Das sagt der Assistent nach dem Speichern.

## Setup-Datei (`--setup-file`)

Für Starts ohne Fragen:

```sh
java -jar VectoraCloud.jar --non-interactive --setup-file /data/setup.yml
```

Das Format und ein vollständiges Beispiel stehen in der [englischen Fassung](setup.md#setup-file---setup-file). Die Regeln:

- Außer `schema-version` und `eula: true` ist alles optional und behält sonst den Vorschlag des Assistenten.
- Die Datei wird so streng gelesen wie `cloud.yml`: Unbekannte Felder, doppelte Schlüssel, falsche Typen oder ein fehlendes `eula: true` sind Fehler. Geprüft wird genau wie bei getippten Antworten (Ports, Heap, Legacy-Server nur mit Legacy-Proxy, kein Docker auf Pterodactyl, Prüfung der ganzen Konfiguration). Bei einem Fehler wird nichts geschrieben, und alle Probleme werden aufgelistet.
- `memory` und `minigames` dürfen nur Gruppen nennen, die es gibt oder die die Einrichtung anlegt. `services` darf nur Tasks nennen, die es gibt oder die die Einrichtung anlegt. Die Proxys gehören nicht dazu, sie laufen immer mit einem Dienst. Jeder Eintrag wird wie eine getippte Antwort geprüft (`statisch ≤ min ≤ max`, max von 1 bis 1000), fehlende Zahlen behalten ihren Vorschlag. `drain-timeout` (pro Gruppe, 5s bis 10m) darf ebenfalls nur Gruppen nennen, die es gibt oder die die Einrichtung anlegt. Der Assistent an der Konsole fragt danach unter den erweiterten Einstellungen, später ändert es `group set <Name> drain-timeout`.
- `node` (mit `max-memory`, mindestens 512M, und `memory-reserve` mit `system`, `node-overhead`, `database`, je 1M bis 64G) ändert den Node, als der diese Cloud läuft (`node-id` in `cloud.yml`), und wird wie `nodes.yml` geprüft. Ein weggelassenes Feld behält seinen Wert oder Default (1G, 256M, 512M). Der Assistent an der Konsole fragt danach unter den erweiterten Einstellungen, später ändert es `node set`. `max-memory: auto` entfernt die Grenze; anderer Text als eine Größe wird abgelehnt.
- Die Datei zählt nur, solange `setup-complete` false ist. Danach wird eine weiterhin gemountete Datei übergangen, damit sie spätere Änderungen über `config set` oder das Dashboard nicht zurückdreht.
- Die Datei enthält kein Secret. Das Passwort einer externen Datenbank kommt aus `secrets/vectora.env` (`VECTORA_DB_PASSWORD`) oder aus `database.password-file`: eine normale Datei (eine Verknüpfung darauf geht, ein FIFO oder Gerät nicht) mit dem Passwort in einer Zeile (ein Zeilenumbruch am Ende wird entfernt), höchstens 4 KiB, UTF-8, relativ zum Home oder absolut. Das passt zu Docker- und Kubernetes-Secrets.

### Warum eine Datei und keine Umgebungsvariablen

- Der Launcher kennt `--setup-file` schon, und die Datei läuft durch denselben strengen YAML-Leser wie die Konfigurationsdateien. Fehler kommen mit Pfad und Zeile.
- Maps und Listen (Speicher je Gruppe, Minigame-Schalter) passen natürlich in YAML. Als Variablen bräuchten sie ein eigenes Namensschema.
- Umgebungsvariablen sind ein schlechter Ort für Secrets: Sie stehen in `docker inspect`, in `/proc/<pid>/environ` und im Pterodactyl-Panel, und jeder Serverprozess, den die Cloud startet, erbt sie. Die Setup-Datei enthält gar kein Secret.
- Beide Wege bräuchten ohnehin dieselbe Prüfung. Ein Format heißt ein Parser, ein Fuzz-Ziel, ein Satz Tests.

Plattformen, die in Variablen denken, können die Datei trotzdem daraus erzeugen, siehe Pterodactyl.

### Pterodactyl

Die Fragen funktionieren in der Panel-Konsole. Ihre Eingaben sieht jeder, der die Konsole öffnen kann, deshalb bietet der Assistent an, das Passwort stattdessen über den Dateimanager in `secrets/vectora.env` einzutragen.

Für ein Egg ohne Fragen erzeugt Wings die Setup-Datei aus Egg-Variablen über `config.files` mit dem Parser `yaml`. Der Startbefehl bekommt `--non-interactive --setup-file setup.yml`. Ein Beispiel steht in der [englischen Fassung](setup.md#pterodactyl). `docker` wird auf Pterodactyl abgelehnt, `SERVER_PORT` ist der Vorschlag für den Java-Port, `SERVER_MEMORY` die Grenze für die Speicherwarnung.

## Wer die Secrets lesen kann

`secrets/vectora.env` ist auf das Konto beschränkt, unter dem die Cloud läuft (siehe [config](config.md#generation-and-storage)). Nach dem Speichern sagt der Assistent, wer trotzdem herankommt:

- **Linux und macOS:** der Besitzer und root.
- **Windows:** der Besitzer und die Mitglieder der lokalen Administratorengruppe. Sie stehen nicht in der Zugriffsliste, aber Windows erlaubt ihnen immer, den Besitz einer Datei zu übernehmen und sie dann zu lesen. Der Gruppenname ist übersetzt (`BUILTIN\Administrators`, `VORDEFINIERT\Administratoren`, …). Der Assistent ermittelt ihn deshalb mit `%SystemRoot%\System32\whoami.exe /groups` und nennt immer die SID `S-1-5-32-544`, die auf jedem Windows gleich ist. Halte diese Gruppe auf einem Server mit der Cloud klein.
- **Dateisysteme ohne Rechte** (FAT, manche Netzlaufwerke): alle, die die Datei erreichen. Das sagt der Assistent offen.

## Was geschrieben wird

In drei Schritten, damit nichts halb fertig als abgeschlossen gilt:

1. alle Änderungen an der Konfiguration in einem `ConfigService.apply`, das als Ganzes geprüft wird. Scheitert das, wird gar nichts geschrieben;
2. erst dann die Secrets: jedes fehlende erzeugte Secret, danach das Passwort einer externen Datenbank. So landet nie ein Passwort ohne den gespeicherten Host und Benutzer dazu;
3. zuletzt `eula-accepted: true` und `setup-complete: true`.

Scheitert Schritt 2 oder 3, kehrt die Konfiguration zum Stand davor zurück (vom Setup angelegte Gruppen und Tasks verschwinden wieder), und ein vom Setup geschriebenes Passwort bekommt seinen alten Wert. Fehlende erzeugte Secrets bleiben, sie ersetzen nie einen Wert. `setup-complete` bleibt false, und der nächste Start fragt erneut. Für jeden gewollten Server ohne Gruppe legt der Assistent Gruppe und Task an, den Task mit den Dienstzahlen aus Schritt 7 (Proxys 1/1/1). Einzeln abgeschaltete Minigame-Gruppen bekommen `wanted: false` in ihrer Gruppendatei.
