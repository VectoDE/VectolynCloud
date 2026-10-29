# Prüfung der Abhängigkeiten

[English](dependency-verification.md)

Jeder Gradle-Lauf prüft alle Abhängigkeiten und Plugins, die er auflöst, gegen die SHA-256-Prüfsummen in [`gradle/verification-metadata.xml`](../gradle/verification-metadata.xml). Das gilt auch für die `.pom`- und `.module`-Dateien. Fehlt eine Prüfsumme oder passt sie nicht, bricht der Build mit `Dependency verification failed` ab. Das ist auf jedem Rechner so, in jedem CI-Job und beim Bau des Docker-Images.

## Was geprüft wird

Die Datei enthält jedes Artefakt jeder auflösbaren Konfiguration:

- den Haupt-Build: Compile-, Runtime- und Test-Classpaths aller Module, das Jazzer-Setup für Fuzz-Tests, die Tasks `e2eTest`/`e2eLiveTest`/`mariadbTest`/`networkTest`, die Classpaths der CodeQL- und Release-Builds
- die `compileOnly`-Server-APIs der Bridges und Plugins: paper-api und velocity-api von PaperMC, pandaspigot-api von repo.hpfxd.com
- `build-logic` (das Kotlin-DSL-Plugin mit seiner Kotlin-Toolchain) und das Settings-Plugin `foojay-resolver-convention`

In `gradle.properties` steht `org.gradle.dependency.verification=strict`. Strict ist bei Gradle ohnehin Standard. Die Zeile steht trotzdem da, damit ein Wechsel auf `lenient` oder `off` im Review auffällt.

Nicht enthalten, weil andere Prüfungen das abdecken:

- Die Gradle-Distribution: `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` und die Wrapper-Prüfung in der CI.
- JDKs, die der Foojay-Resolver für eine Toolchain lädt. Das ist Toolchain-Bereitstellung, keine Auflösung von Abhängigkeiten. Die CI installiert ihre JDKs mit `actions/setup-java`.
- Server-Software, die die Cloud zur Laufzeit lädt. Sie hat eigene Prüfsummen, siehe [docs/cloud/download.md](cloud/download.md).
- `-sources.jar` und `-javadoc.jar`, die nur IDEs laden. Sie liegen nie auf einem Classpath und gelten als vertrauenswürdig, damit „Download sources“ in der IDE weiter funktioniert.

## Metadaten aktualisieren

Nach jeder Änderung an Abhängigkeiten, Plugins oder Repositories:

```sh
bash gradle/update-verification-metadata.sh
git diff -- gradle/verification-metadata.xml
```

Unter Windows in Git Bash ausführen. Das Skript behält den Block `<configuration>`, entfernt alle Einträge und lässt Gradle sie neu schreiben (`./gradlew --write-verification-metadata sha256 help`). Mit diesem Schalter löst der Task `help` jede Konfiguration auf, die nach der Konfigurationsphase existiert. `--refresh-dependencies` sorgt dafür, dass Gradle die Parent- und BOM-`.pom`-Dateien neu liest, die ein gefüllter lokaler Cache sonst überspringt. Einträge entfernter Abhängigkeiten verschwinden. Eine Konfiguration, die ein Plugin erst während eines Tasks anlegt, ist nicht dabei. Dann schlägt die CI mit einer fehlenden Prüfsumme fehl, und man ergänzt die Abhängigkeit, indem man den betreffenden Task mit `--write-verification-metadata sha256` ausführt. Die Datei gehört in denselben Pull Request wie die Änderung an den Abhängigkeiten.

Prüfsummen nicht von Hand ändern und `--write-verification-metadata` nicht allein aufrufen. Das Skript enthält auch den PandaSpigot-Workaround, der weiter unten beschrieben ist.

### Checkliste fürs Review

Jeder Eintrag ist Trust on First Use: Das Skript hält fest, was die Repositories in diesem Moment ausliefern. Erst das Review macht daraus eine Prüfung.

1. Nur die erwarteten Komponenten haben sich geändert. Ein Versionssprung ersetzt die Einträge dieser Version. Neue Einträge sollten sich durch neue transitive Abhängigkeiten erklären lassen.
2. Neue Gruppen oder ein neues Repository genauer ansehen: Wer veröffentlicht das, wofür wird es gebraucht?
3. Bei Artefakten außerhalb von Maven Central (PaperMC, repo.hpfxd.com) mindestens das Haupt-Jar mit der Prüfsumme vergleichen, die das Repository veröffentlicht:

   ```sh
   curl -fsS https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/<version>/paper-api-<version>.jar.sha256
   ```

   Der Wert muss dem `sha256` dieses Artefakts in der Datei entsprechen.

## Dependabot

Dependabot aktualisiert `gradle/libs.versions.toml` und die Build-Dateien, aber nicht `gradle/verification-metadata.xml`. Seine Pull Requests schlagen deshalb zunächst fehl. Der Workflow [`dependabot-verification-metadata.yml`](../.github/workflows/dependabot-verification-metadata.yml) behebt das:

1. Dependabot öffnet oder aktualisiert einen Pull Request, der `gradle/**`, eine `*.gradle.kts`-Datei oder `gradle.properties` ändert.
2. Der Job `regenerate` checkt den Dependabot-Commit aus und führt `gradle/update-verification-metadata.sh` aus. Er hat ein Token nur mit Leserechten und keine gespeicherten Zugangsdaten. Ändert sich mehr als die Metadaten-Datei, schlägt er fehl.
3. Der Job `commit` hat als einziger `contents: write` und führt keinen Code aus dem Pull Request aus. Er prüft, dass das Artefakt genau eine normale Metadaten-Datei ist, committet sie mit Sign-off als `github-actions[bot]` und pusht sie auf den Dependabot-Branch. Hat Dependabot den Branch inzwischen verschoben, schlägt der Push fehl, und der nächste Lauf übernimmt.
4. Ein Push mit `GITHUB_TOKEN` startet keine anderen Workflows. Ein Maintainer prüft den Commit nach der Checkliste oben und pusht danach einen leeren Commit auf den Branch. Dann laufen CI, CodeQL und Dependency Review auf dem neuen Stand:

   ```sh
   gh pr checkout <n>
   git commit --allow-empty -s -m "ci: run checks"
   git push
   ```

   Den Pull Request dafür nicht schließen und wieder öffnen: Beim Schließen wertet Dependabot das Update als abgelehnt.

Der Workflow nutzt das normale Ereignis `pull_request`, nie `pull_request_target`. Er läuft nur, wenn Dependabot den Pull Request aus einem Branch dieses Repositorys geöffnet und den Lauf ausgelöst hat. Daten aus dem Pull Request erreichen die Shell nur über Umgebungsvariablen. Alle Actions sind per Commit-SHA festgelegt.

Sobald ein Branch fremde Commits hat, rebaset Dependabot ihn nicht mehr von selbst. `@dependabot recreate` baut den Branch neu, danach schreibt der Workflow die Metadaten erneut. Ohne den Workflow geht es von Hand genauso: Dependabot-Branch auschecken, Skript ausführen, prüfen, mit `-s` committen, pushen.

## Warum SHA-256 und keine PGP-Signaturen

Gradle kann auch PGP-Signaturen prüfen und die öffentlichen Schlüssel im Repository ablegen. Wir haben das am 2026-10-29 ausprobiert (`--write-verification-metadata pgp,sha256 --export-keys`) und uns vorerst dagegen entschieden:

- **Gerade die riskantesten Artefakte sind nicht signiert.** paper-api, velocity-api, brigadier (Mojang), die md-5-Artefakte, pandaspigot-api und die Marker-Dateien der Gradle-Plugins haben keine Signaturen. Sie kommen aus Repositories außerhalb von Maven Central, eines davon betreibt eine Einzelperson. Für sie gäbe es ohnehin nur Prüfsummen.
- **Viele Schlüssel sind nicht abrufbar.** 13 Signaturschlüssel lagen weder auf keys.openpgp.org noch auf keyserver.ubuntu.com. Ihre 84 Artefakte, darunter Netty, JLine, Jazzer, SLF4J und Teile von Adventure, fielen ebenfalls auf Prüfsummen zurück. Von den Bibliotheken, die VectoraCloud ausliefert, wären nur Jackson, der MariaDB-Treiber und SnakeYAML per Signatur geprüft worden.
- **Auch vertrauenswürdige Schlüssel wären Trust on First Use.** Gradle vertraut jedem Schlüssel, den es herunterlädt, und weitet das Vertrauen auf ganze Gruppen aus (zum Beispiel ein Schlüssel für alles unter `com.google.*`). Stärker als Prüfsummen wird das erst, wenn man jeden Fingerprint mit dem veröffentlichten Schlüssel des jeweiligen Projekts abgleicht, und das für mehr als 40 Schlüssel.
- **Im Review ist weniger zu sehen.** Mit einem vertrauenswürdigen Schlüssel ändert ein Versionssprung nichts an der Datei. Mit Prüfsummen taucht jedes neue Artefakt im Diff auf.
- **Abhängigkeit vom Netz.** Das Schreiben der Datei hinge in jedem Dependabot-Lauf von Key-Servern ab.

Einen echten Zusatzschutz hätte PGP: gegen eine Kontoübernahme, bei der jemand eine neue Version mit einem anderen Schlüssel signiert veröffentlicht. Wir prüfen das erneut, sobald die Schlüssel von Netty und JLine abrufbar sind und PaperMC seine Artefakte signiert. Bis dahin beruht der Schutz auf dem Review der Prüfsummen.

## PandaSpigot-Workaround

pandaspigot-api gibt es nur als Snapshot, deshalb legt der Build einen eindeutigen Snapshot fest (`1.8.8-R0.1-20251003.170525-12`). Gradle 9.8.2 scheitert beim Schreiben der Metadaten für eine solche Version (`Multiple entries with same key`), sobald Metadaten geprüft werden. Das Lesen funktioniert. Das Skript vertraut deshalb während des Schreibens der `.pom` und `.module` von pandaspigot-api und ersetzt diesen Eintrag danach durch ihre SHA-256-Werte aus dem Gradle-Cache. In der eingecheckten Datei werden sie wie alles andere geprüft. Bleibt der vorübergehende Eintrag stehen, schlägt die CI fehl.

## Wenn die Prüfung fehlschlägt

- **Du hast Abhängigkeiten geändert:** Skript ausführen, prüfen, committen.
- **Du hast nichts geändert:** Nicht neu schreiben. Erst herausfinden, welches Artefakt betroffen ist. Einen beschädigten lokalen Cache behebt `./gradlew build --refresh-dependencies`. Bleibt der Fehler, liefert das Repository andere Bytes aus als vorher. Das ist ein Grund für eine [Sicherheitsmeldung](../SECURITY.md).
- Gradle schreibt einen Bericht nach `build/reports/dependency-verification/`.

## CI

- Der Job `Validate Gradle wrapper` prüft: Die Datei existiert, prüft Metadaten und hat SHA-256-Einträge. Sie enthält keine XML-Kommentare, keine PGP-Schlüssel, keinen übrig gebliebenen Workaround-Eintrag und keine vertrauenswürdigen Artefakte außer `-javadoc.jar`/`-sources.jar`. `gradle.properties` setzt `strict`. Kein Workflow, keine Gradle-Build-Datei, keine Properties-Datei und kein Dockerfile schaltet die Prüfung ab oder schreibt die Datei neu (lange Optionen, `-F`/`-M`, die Property in jeder Schreibweise).
- Der Dependabot-Workflow übernimmt eine neu geschriebene Datei nur, wenn ihr Block `<configuration>` unverändert ist.
- Alle anderen Jobs führen Gradle mit eingeschalteter Prüfung aus. Eine fehlende oder falsche Prüfsumme lässt also `Build`, `Tests on Java 21`, `Integration`, CodeQL und das Release scheitern.
- Änderungen unter `gradle/`, `build-logic/`, `settings.gradle.kts` oder `gradle.properties` starten auch den Job `Integration`.
