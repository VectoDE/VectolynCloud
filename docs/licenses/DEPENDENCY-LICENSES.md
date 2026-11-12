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
| org.postgresql:postgresql | 42.7.14 | implementation | BSD-2-Clause (transitiv: org.checkerframework:checker-qual, MIT) | yes |
| com.code-intelligence:jazzer-junit | 0.30.0 | test (fuzz) | Apache-2.0 | yes |

## Web panel frontend (npm, `cloud/panel/web`)

Exakte Versionen in `cloud/panel/web/package-lock.json`. Installiert wird nur mit `npm ci --ignore-scripts`, Installationsskripte laufen nicht. Ausgeliefert wird allein der statische Export (`next build`) im Jar; Node.js läuft zur Laufzeit nicht. Lizenzen laut npm-Registry (`npm view <paket> license`, Stand 2026-11-12).

| Package | Version | Scope | License (SPDX) | GPL-3.0 compatible |
|---|---|---|---|---|
| next | 16.4.3 | build, Laufzeitcode im Export | MIT | yes |
| react | 19.3.1 | build, Laufzeitcode im Export | MIT | yes |
| react-dom | 19.3.1 | build, Laufzeitcode im Export | MIT | yes |
| tailwindcss | 4.3.3 | build only | MIT | yes |
| @tailwindcss/postcss | 4.3.3 | build only | MIT | yes |
| postcss | 8.5.32 | build only | MIT | yes |
| typescript | 5.9.3 | build only | Apache-2.0 | yes |
| @types/node, @types/react, @types/react-dom | 24.x, 19.3.0 | build only | MIT | yes |
| @img/sharp-* (optional, transitiv über next) | 0.34.x / 1.3.x | nur Bauzeit, wird nicht aufgerufen | LGPL-3.0-or-later, teils mit Apache-2.0/MIT | yes |

`sharp` ist eine optionale Abhängigkeit von Next.js für `next/image`. Der Export läuft mit `images.unoptimized`, sharp wird also nie aufgerufen; npm installiert nur das Paket der Bau-Plattform, und nichts davon kommt ins Jar. Die Trivy-Lizenzprüfung nimmt genau diese Lizenzen für `cloud/panel/web/package-lock.json` aus (`.trivyignore.yaml`).

Schriften und Design-Tokens im Panel kommen aus `branding/` (OFL-1.1 bzw. CC-BY-SA-4.0, siehe `REUSE.toml`).

## Website (`site/`, published on GitHub Pages)

Everything is vendored into the repository; the website loads nothing from other servers. REUSE annotations are in `REUSE.toml`.

| Component | Version / source | File in the repository | License (SPDX) | GPL-3.0 compatible |
|---|---|---|---|---|
| three.js | 0.170.0, npm tarball `sha512-FQK+LEpYc0fBD+J8g6oSEyyNzjp+Q7Ks1C568WWaoMRLW+TkNNWmenWeGgJjV105Gd+p/2ql1ZcjYvNiPZBhuQ==`, `build/three.module.min.js` unchanged (SHA-256 `08fd7545d13d2c7fb65ab691530a802dafefd638596501854f267d0fb13c39e7`) | `site/assets/vendor/three/` | MIT | yes |
| Inter | google/fonts `ofl/inter` at `0b58fb370093f9a9f4ff785d94405710b79de67c` (`Inter[opsz,wght].ttf`, SHA-256 `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`), opsz pinned to 14, Latin subset, WOFF2 | `site/assets/fonts/inter.woff2` | OFL-1.1 | yes (font, not combined into the program) |
| Pixelify Sans | subset of `branding/fonts/pixelify-sans/PixelifySans-Variable.ttf`, WOFF2 | `site/assets/fonts/pixelify-sans.woff2` | OFL-1.1 | yes |
| JetBrains Mono | subset of `branding/fonts/jetbrains-mono/JetBrainsMono-Variable.ttf`, WOFF2 | `site/assets/fonts/jetbrains-mono.woff2` | OFL-1.1 | yes |

The subsets were made with fontTools (`pyftsubset`, Latin-1 plus punctuation, € and arrows). The OFL allows modified versions under the same license; none of the three fonts declares a Reserved Font Name. Each license text sits next to the file (`site/assets/fonts/OFL-*.txt`).

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

- Direct Gradle dependencies: 22 (davon 3 BOMs), plus 2 Build-Werkzeuge und 5 externe Ausnahmen.
- Unknown licenses: 0.
- Conflicts with GPL-3.0: none found. Hinweise: JUnit (EPL-2.0) ist nur Testabhängigkeit und wird nicht ausgeliefert. MariaDB Connector/J (LGPL-2.1-or-later) ist mit GPL-3.0 kombinierbar. Der PostgreSQL-JDBC-Treiber (BSD-2-Clause) ebenso.
