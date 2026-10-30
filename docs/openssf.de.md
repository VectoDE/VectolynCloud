# OpenSSF Scorecard und die Aufräum-Prüfung

[English](openssf.md)

Das Repository lässt [OpenSSF Scorecard](https://scorecard.dev/viewer/?uri=github.com/VectoDE/VectolynCloud) bei jedem Push auf `main`, wöchentlich und auf Abruf laufen ([`scorecard.yml`](../.github/workflows/scorecard.yml)). Die Funde stehen unter *Security and quality* neben denen von CodeQL. Außerdem hat das Projekt das [OpenSSF-Best-Practices-Badge](https://www.bestpractices.dev/projects/16026).

## Die Aufräum-Prüfung

Sie läuft am Ende jeder Etappe und vor jedem Release-Tag. Eine Etappe ist erst abgeschlossen und ein Tag darf erst gepusht werden, wenn alles davon stimmt:

1. Kein Pull Request ist offen. Ein Dependabot-Pull-Request wird entweder mit den Code-Änderungen gemergt, die er braucht, oder mit einem Kommentar geschlossen, der den Grund nennt.
2. Unter *Security and quality* steht kein offener Fund von CodeQL, Scorecard, Dependabot oder Secret Scanning, der sich beheben lässt. Funde werden im Code, in Workflows, Einstellungen oder Dateien behoben. Nichts wird von Hand geschlossen und nichts im Code unterdrückt.
3. CI, CodeQL, Dependency Review und Scorecard sind nach dem letzten Merge auf dem neuesten Commit von `main` gelaufen. Bringt ein Lauf etwas Neues hervor, wird es behoben, und die Läufe wiederholen sich, bis nichts Behebbares übrig ist.
4. Was offen bleibt, steht unten mit Begründung.

Die erste Runde dieser Prüfung war [Issue #71](https://github.com/VectoDE/VectolynCloud/issues/71).

## Was umgesetzt ist

- **Pinned-Dependencies:** Jede Action ist auf einen Commit-SHA gepinnt, jedes Image (Dockerfile, Compose-Datei, actionlint) auf einen Digest. Das Release erzeugt seine Provenance mit `actions/attest-build-provenance` statt mit slsa-github-generator, weil sich der Generator nur über einen Tag einbinden lässt ([operations.de.md](cloud/operations.de.md#ein-release-prüfen)).
- **Binary-Artifacts:** Die einzige Binärdatei ist `gradle/wrapper/gradle-wrapper.jar`. [`gradle-wrapper.yml`](../.github/workflows/gradle-wrapper.yml) prüft sie gegen die Prüfsummen, die Gradle veröffentlicht, und `distributionSha256Sum` pinnt die Distribution. Scorecard akzeptiert die Datei nur, wenn dieser Workflow zum Zeitpunkt der Analyse einen erfolgreichen Lauf auf dem neuesten Commit von `main` hat. Deshalb wartet `scorecard.yml` auf ihn.
- **Fuzzing:** Jazzer-Fuzz-Tests in den Modulen, in jedem Testlauf mit den gespeicherten Eingaben wiederholt und jede Nacht gefuzzt ([`fuzz-nightly.yml`](../.github/workflows/fuzz-nightly.yml)). Scorecard erkennt Jazzers JUnit-`@FuzzTest` nicht (es kennt OSS-Fuzz, ClusterFuzzLite und einige sprachspezifische Bibliotheken, keine für Java). Deshalb läuft in [`cflite-pr.yml`](../.github/workflows/cflite-pr.yml) bei Pull Requests mit Code-Änderungen zusätzlich [ClusterFuzzLite](https://google.github.io/clusterfuzzlite/) mit dem Sanitizer `address`. Die Ziele liegen in [`.clusterfuzzlite/fuzzers`](../.clusterfuzzlite/fuzzers): Platzhalter-Renderer, Kommandozeilen-Parser der Konsole, Cron-Parser und Versions-Parser der Datenbank. Es sind eigene Harnesses, weil das OSS-Fuzz-JVM-Image mit Java 17 läuft und weder den Gradle-Build noch Java-21-Klassen laden kann: `build.sh` übersetzt die Quellen dieser reinen Parser mit `--release 17`. Parser, die Java 21 oder Bibliotheken brauchen, bleiben bei den Jazzer-Tests, bis das Image ein neueres JDK hat.
- **Dependabot und Major-Versionen:** Adventure (festgelegt auf die Version, die PandaSpigot deklariert) und JLine 4 (Major) sind in [`dependabot.yml`](../.github/dependabot.yml) ausgeschlossen, der Grund steht jeweils neben der Regel. MariaDB folgt der Long-Term-Support-Reihe.
- **Prüfung der Abhängigkeiten:** Jede Gradle-Abhängigkeit wird gegen eine SHA-256-Prüfsumme geprüft, und die CI schlägt fehl, wenn ein Pull Request das abschwächt ([dependency-verification.de.md](dependency-verification.de.md)).
- **Ausgehender Verkehr:** Jeder Job startet `step-security/harden-runner` mit `egress-policy: block` und einer eigenen Liste erlaubter Hosts. Braucht ein Job einen neuen Host, schlägt er fehl, bis der Host im Review ergänzt ist. Unter Windows wird der Agent von harden-runner erst etwa eine Minute nach Jobbeginn aktiv und bricht dann laufende Downloads ab. Die Windows-Jobs protokollieren deshalb nur.

## Was vorerst nicht 10 erreicht

| Check | Grund | Ändert sich, wenn |
|---|---|---|
| Maintained | Scorecard gibt jedem Repository, das jünger als 90 Tage ist, eine 0, egal wie aktiv es ist. Das Repository wurde am 28.10.2026 angelegt. | von selbst, 90 Tage nach dem Anlegen |
| Code-Review | Scorecard zählt gemergte Änderungen mit einem zustimmenden Review von jemand anderem als dem Autor. Es gibt einen Maintainer, und GitHub lässt Autoren ihre eigenen Pull Requests nicht freigeben. Der Wert bleibt also 0. Jede Änderung läuft trotzdem über einen Pull Request mit allen Pflicht-Checks. | ein zweiter Maintainer reviewt |
| Branch-Protection | Das Ruleset für `main` verlangt Pull Requests (nur Squash), lineare Historie, strikte Pflicht-Checks und erledigte Review-Threads, verbietet Löschen und Force-Push und erlaubt keinen Bypass. Scorecard will zusätzlich mindestens eine Pflicht-Freigabe, Code-Owner-Review und die Freigabe des letzten Pushs. Dafür braucht es jeweils eine zweite Person; mit einem Maintainer würde das jeden Merge blockieren. | ein zweiter Maintainer kommt dazu; dann das Ruleset anheben |
| Contributors | Zählt Beitragende aus verschiedenen Organisationen. | mehr Beitragende |
| CII-Best-Practices | Passing-Badge. Silver verlangt unter anderem eine zweite Person mit Schreibrechten und Reviews fremder Änderungen. | zusammen mit Code-Review |
| Vulnerabilities | Scorecard liest `gradle/verification-metadata.xml`. Alle behebbaren Funde sind gepatcht ([dependency-verification.de.md](dependency-verification.de.md#verwundbare-transitive-versionen)). Zwei bleiben: commons-lang 2.6, wofür es keine behobene Version gibt und das der Bukkit-1.8.8-Test-Classpath braucht, und das Kotlin-Gradle-Plugin des Gradle-Builds, nur in einer Beta behoben. | Gradle liefert ein behobenes Kotlin; PandaSpigot gibt commons-lang 2.x auf |
