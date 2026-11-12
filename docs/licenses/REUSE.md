# REUSE plan

Ziel: Jede Datei im Repository trägt eine maschinenlesbare Lizenzangabe nach der [REUSE-Spezifikation](https://reuse.software/spec/), und `reuse lint` läuft in der CI grün.

## Current state (2026-11-12)

Von 976 getrackten Quelldateien hat keine einen `SPDX-License-Identifier`-Header:

| Type | Files | Without SPDX header |
|---|---|---|
| Java (`*.java`) | 947 | 947 |
| Kotlin (`*.kt`) | 1 | 1 |
| Gradle Kotlin DSL (`*.kts`) | 28 | 28 |
| TypeScript/JavaScript | 0 | 0 |
| **Total** | **976** | **976** |

Das Projekt hat bisher kein Web-Panel mit TypeScript. Kommt es dazu, gilt dieselbe Regel.

## Plan

1. Header für Quelltext: jede Java-, Kotlin- und Skriptdatei bekommt am Dateianfang

   ```java
   // SPDX-License-Identifier: GPL-3.0-or-later
   // SPDX-FileCopyrightText: 2026 VectolynCloud contributors
   ```

   Das Einfügen läuft als eigener, mechanischer PR (Skript oder `reuse annotate`), getrennt von inhaltlichen Änderungen.
2. `REUSE.toml` für alles, was keinen Kommentar-Header tragen kann: Markdown, YAML, JSON, Bilder, Ressourcenpakete, Testdaten (zum Beispiel `cloud/download/src/test/resources/**`), Gradle Wrapper, Lockfiles. Fremde Dateien bekommen ihre eigene Lizenz in `REUSE.toml`.
3. `LICENSES/` mit den Lizenztexten (`reuse download --all`): `GPL-3.0-or-later` sowie jede weitere im Repo vorkommende Lizenz.
4. CI: Der Workflow `fsfe/reuse-action` läuft bei jedem PR. Der Check wird Pflicht, sobald er grün ist.
5. Ergebnis ist eine Siegel-Anzeige: Das REUSE-Badge kommt erst in die README, wenn `reuse lint` wirklich besteht.

## Open decisions

- Copyright-Zeile (`SPDX-FileCopyrightText`): Wortlaut legt der Inhaber fest.
- Branding und Ressourcenpaket (`branding/`, `resourcepack/`): Lizenz der Grafiken ist zu klären, bevor sie in `REUSE.toml` stehen.
