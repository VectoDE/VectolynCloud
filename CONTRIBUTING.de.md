# Mitmachen bei Vectora

[English](CONTRIBUTING.md)

Danke, dass du helfen willst. Vectora steht noch am Anfang, deshalb sind die Regeln kurz. Sie wachsen mit dem Code.

## Bevor du anfängst

- Lies das [README](README.de.md) und die [Roadmap](https://github.com/users/VectoDE/projects/11).
- Fragen und lose Ideen gehören in die [Discussions](https://github.com/VectoDE/VectoraCloud/discussions).
- Für einen Fehler oder ein konkretes Feature öffnest du ein Issue über eines der Formulare. Bei allem, was größer ist als ein kleiner Fix, warte auf eine Antwort im Issue, bevor du Code schreibst. Dann arbeitest du nicht umsonst, falls die Änderung nicht zum Plan passt.
- Sicherheitslücken: siehe [SECURITY.md](SECURITY.md). Nie in einem öffentlichen Issue.

## Ablauf

1. Repository forken und von `main` einen Branch anlegen (`fix/end-portal-return`, `feat/lobby-navigator`).
2. Kleine, abgegrenzte Commits machen.
3. Vor dem Push `./gradlew build` ausführen. Um eine Änderung in einem laufenden Netzwerk auszuprobieren, startest du es mit `./gradlew :cloud:node:runLocal`; es läuft in `run/`, das Git ignoriert.
4. Pull Request gegen `main` öffnen und die Vorlage ausfüllen. Das Issue mit `Fixes #123` verknüpfen.
5. CI, CodeQL und Dependency Review müssen grün sein, und alle Review-Konversationen müssen aufgelöst sein. Ein Maintainer prüft und merged per Squash. Auf `main` gibt es keine direkten Pushes und keine Force-Pushes.

## Commit-Nachrichten

Wir nutzen [Conventional Commits](https://www.conventionalcommits.org/de/), auf Englisch:

```
feat(lobby): add double jump
fix(proxy): keep Bedrock players on the lobby after restart
```

Übliche Typen: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`. Der PR-Titel folgt demselben Format, weil daraus der Squash-Commit wird.

## Sign-off (DCO)

Jeder Commit braucht eine Zeile `Signed-off-by`. Damit bestätigst du das [Developer Certificate of Origin](https://developercertificate.org/): Du hast die Änderung selbst geschrieben oder darfst sie unter GPL-3.0 einreichen.

```sh
git commit -s -m "fix(end): return players to their origin server"
```

Vergessen? `git commit --amend -s` für den letzten Commit, `git rebase --signoff main` für mehrere.

## Code

- Java mit Gradle (Kotlin DSL). Die Toolchain ist Java 25, das Bytecode-Ziel Java 21. Die CI baut und testet mit JDK 25 und führt die Tests zusätzlich auf Java 21 aus (`./gradlew test -PtestJava=21`). Tests, die Paper- oder Velocity-Klassen laden, tragen `@Tag("requires-java25")`. Gemeinsame Build-Einstellungen stehen in den Convention-Plugins unter `build-logic/`.
- Code, der Java 25 braucht, bekommt `@RequiresJava25`, eine Laufzeitprüfung und einen Java-21-Weg. Details in `docs/java-compatibility.md`.
- Ein Formatter ist noch nicht eingerichtet. Halte dich an den umgebenden Code: 4 Leerzeichen, keine Wildcard-Imports, klare Namen statt Kommentare.
- Neue Logik braucht Tests. Zu einem Bugfix gehört, wo es machbar ist, ein Test, der ohne den Fix fehlschlägt.
- Code, der nicht vertrauenswürdige Eingaben verarbeitet (Netzwerk-Frames, Konfigurationsdateien, Downloads, API-Anfragen), bekommt zusätzlich einen Jazzer-`@FuzzTest`. Siehe [docs/cloud/architecture.md](docs/cloud/architecture.md#fuzz-tests).
- Jede Abhängigkeit und jedes Plugin wird gegen SHA-256-Prüfsummen in `gradle/verification-metadata.xml` geprüft. Wenn du eine hinzufügst, entfernst oder aktualisierst, führe `bash gradle/update-verification-metadata.sh` aus, prüfe den Diff und committe ihn mit deiner Änderung. Siehe [docs/dependency-verification.de.md](docs/dependency-verification.de.md).
- Keine neuen Fremd-Plugins. Die erlaubte Basis steht im README, alles andere entsteht in diesem Repository.
- Jeder Spielmodus muss für Bedrock-Spieler funktionieren. Menüs sind Inventar-Menüs, keine Bedrock-Formulare.
- Nie Secrets, IPs, Spielerdaten, Welten oder Server-Jars committen. `templates/` enthält nur Vorlagen; Server-Laufzeiten liegen in `run/` (lokale Entwicklung, von Git ignoriert) oder in einem Cloud-Home außerhalb des Repositorys.

## Sprachen

- Code, Bezeichner, Commit-Nachrichten und Code-Kommentare: Englisch.
- Alles, was Spieler oder Admins lesen (Nachrichten, Menüs, README, Wiki): Englisch und Deutsch. Ingame richtet sich der Text nach der Client-Sprache.
- Wiki-Seiten liegen in `docs/wiki/`. Englische Seiten haben kein Suffix, deutsche enden auf `-DE` (`Architecture.md`, `Architecture-DE.md`). Ein Workflow spiegelt sie ins GitHub-Wiki. Bearbeite sie also hier und nicht im Wiki.
- Issues und Discussions: Deutsch oder Englisch, wie es dir lieber ist.

## Lizenz

Mit deinem Beitrag stimmst du zu, dass er unter der [GPL-3.0](LICENSE) steht.
