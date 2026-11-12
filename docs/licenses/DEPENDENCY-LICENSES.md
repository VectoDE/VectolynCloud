# Dependency licenses

Abhängigkeits-Inventar für VectolynCloud (Issue E1.4). Das Projekt steht unter GPL-3.0-or-later (siehe `LICENSE`). Diese Liste hält fest, welche direkten Abhängigkeiten eingebunden sind und unter welcher Lizenz sie stehen.

Quelle der Liste: `gradle/libs.versions.toml` und die `build.gradle.kts` aller Module. Die Lizenzen stammen von den Projektseiten der Hersteller (Stand 2026-11-12). Sie wurden nicht aus den heruntergeladenen POMs ausgelesen. Ein späterer Schritt soll die Prüfung automatisieren (zum Beispiel mit einem Gradle-Lizenzbericht oder REUSE, siehe `REUSE.md`).

Transitive Abhängigkeiten sind nicht einzeln aufgeführt. Die Verifikation der Artefakte steht in `docs/dependency-verification.md`.

## Direct dependencies (Gradle)

| Library | Version | Scope | License (SPDX) | GPL-3.0 compatible |
|---|---|---|---|---|
| io.papermc.paper:paper-api | 26.3.build.226-stable | compileOnly, test | MIT | yes |
| com.velocitypowered:velocity-api | 4.3.1 | compileOnly, test | MIT | yes |
| com.hpfxd.pandaspigot:pandaspigot-api | 1.8.8-R0.1-20251003.170525-12 | compileOnly, test | GPL-3.0-only (Bukkit/Spigot API lineage) | yes |
| net.kyori:adventure-api | 4.24.0 | compileOnly | MIT | yes |
| org.junit:junit-bom | 6.1.3 | test | EPL-2.0 | n/a, test only, not distributed |
| org.junit.jupiter:junit-jupiter | 6.1.3 | test | EPL-2.0 | n/a, test only, not distributed |
| org.junit.platform:junit-platform-launcher | 6.1.3 | test runtime | EPL-2.0 | n/a, test only, not distributed |
| org.yaml:snakeyaml | 2.7 | implementation, test | Apache-2.0 | yes |
| io.netty:netty-bom | 4.2.20.Final | platform | Apache-2.0 | yes |
| io.netty:netty-transport | 4.2.20.Final | implementation, api | Apache-2.0 | yes |
| io.netty:netty-handler | 4.2.20.Final | implementation | Apache-2.0 | yes |
| io.netty:netty-codec-http | 4.2.20.Final | implementation | Apache-2.0 | yes |
| io.netty:netty-codec-http2 | 4.2.20.Final | implementation | Apache-2.0 | yes |
| org.jline:jline-terminal | 3.30.17 | implementation | BSD-3-Clause | yes |
| org.jline:jline-reader | 3.30.17 | implementation | BSD-3-Clause | yes |
| org.jline:jline-terminal-jni | 3.30.17 | runtimeOnly | BSD-3-Clause | yes |
| org.jline:jline-terminal-ffm | 3.30.17 | runtimeOnly | BSD-3-Clause | yes |
| tools.jackson:jackson-bom | 3.2.4 | platform | Apache-2.0 | yes |
| tools.jackson.core:jackson-databind | 3.2.4 | implementation, api | Apache-2.0 | yes |
| tools.jackson.dataformat:jackson-dataformat-yaml | 3.2.4 | implementation, test | Apache-2.0 | yes |
| com.graphql-java:graphql-java | 26.1 | implementation | MIT | yes |
| com.graphql-java:java-dataloader | 6.0.0 | runtime (via graphql-java) | Apache-2.0 | yes |
| org.reactivestreams:reactive-streams | 1.0.3 | runtime (via graphql-java) | MIT-0 | yes |
| org.jspecify:jspecify | 1.0.0 | runtime (via graphql-java) | Apache-2.0 | yes |
| org.mariadb.jdbc:mariadb-java-client | 4.0.0 | implementation | LGPL-2.1-or-later | yes (the "or later" clause allows combination under GPL-3.0) |
| com.code-intelligence:jazzer-junit | 0.30.0 | test (fuzz) | Apache-2.0 | yes |

## Build tooling (not shipped)

| Component | License | Note |
|---|---|---|
| org.gradle.toolchains.foojay-resolver-convention 1.0.0 | Apache-2.0 | settings plugin |
| Gradle `kotlin-dsl` (build-logic) | Apache-2.0 | build only |

## Bewusste Ausnahmen: externe Server-Software

Geyser, Floodgate, ViaVersion, ViaBackwards und ViaRewind bleiben extern und sind bewusst keine Eigenentwicklung (Entscheidung im Zielbild). Sie sind keine Gradle-Abhängigkeiten. Die Cloud lädt sie zur Laufzeit über den Download-Katalog (`cloud/download/src/main/resources/net/vectolyn/cloud/download/catalog/catalog.yml`) und startet sie als eigene Plugins auf den Diensten. Sie sind nicht im VectolynCloud-Jar gebündelt und werden nicht mit ihm gelinkt.

| Component | License (SPDX) | Status |
|---|---|---|
| Geyser | MIT | bewusste Ausnahme, extern, Laufzeit-Download |
| Floodgate | MIT | bewusste Ausnahme, extern, Laufzeit-Download |
| ViaVersion | GPL-3.0-only | bewusste Ausnahme, extern, Laufzeit-Download |
| ViaBackwards | GPL-3.0-only | bewusste Ausnahme, extern, Laufzeit-Download |
| ViaRewind | GPL-3.0-only | bewusste Ausnahme, extern, Laufzeit-Download |

## Summary

- Direct Gradle dependencies: 21 (davon 3 BOMs), plus 2 Build-Werkzeuge und 5 externe Ausnahmen.
- Unknown licenses: 0.
- Conflicts with GPL-3.0: none found. Hinweise: JUnit (EPL-2.0) ist nur Testabhängigkeit und wird nicht ausgeliefert. MariaDB Connector/J (LGPL-2.1-or-later) ist mit GPL-3.0 kombinierbar.
