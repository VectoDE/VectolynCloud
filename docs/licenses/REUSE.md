# REUSE plan

Ziel: Jede Datei im Repository trägt eine maschinenlesbare Lizenzangabe nach der [REUSE-Spezifikation](https://reuse.software/spec/), und `reuse lint` läuft in der CI grün.

## Current state (2026-11-12)

`reuse lint` besteht (REUSE 3.3). Alle 1627 Dateien tragen Copyright- und Lizenzangabe.

| Weg | Dateien | Lizenz |
|---|---|---|
| `SPDX`-Header im Kopf (`*.java`, `*.kt`, `*.kts`, `*.sh`, `*.ps1`, `*.py`) | 1002 | GPL-3.0-or-later |
| `REUSE.toml` (Markdown, YAML, JSON, Bilder, Testdaten, Lockfiles, Gradle Wrapper, Rest) | 625 | GPL-3.0-or-later |
| `REUSE.toml`: `branding/**`, `resourcepack/**` | im Posten oben enthalten | CC-BY-SA-4.0 |
| `REUSE.toml`: Schriften in `branding/fonts/*` | im Posten oben enthalten | OFL-1.1 |
| `REUSE.toml`: `gradle/wrapper/gradle-wrapper.jar` | 1 | Apache-2.0 |

Copyright-Zeile überall: `2026 VectoDE`. Lizenztexte liegen in `LICENSES/`.

## Plan

1. Header für Quelltext: jede Java-, Kotlin- und Skriptdatei bekommt am Dateianfang

   ```java
   // SPDX-License-Identifier: GPL-3.0-or-later
   // SPDX-FileCopyrightText: 2026 VectoDE
   ```

   Das Einfügen läuft als eigener, mechanischer PR (Skript oder `reuse annotate`), getrennt von inhaltlichen Änderungen.
2. `REUSE.toml` für alles, was keinen Kommentar-Header tragen kann: Markdown, YAML, JSON, Bilder, Ressourcenpakete, Testdaten (zum Beispiel `cloud/download/src/test/resources/**`), Gradle Wrapper, Lockfiles. Fremde Dateien bekommen ihre eigene Lizenz in `REUSE.toml`.
3. `LICENSES/` mit den Lizenztexten (`reuse download --all`): `GPL-3.0-or-later` sowie jede weitere im Repo vorkommende Lizenz.
4. CI: Der Workflow `fsfe/reuse-action` läuft bei jedem PR. Der Check wird Pflicht, sobald er grün ist.
5. Ergebnis ist eine Siegel-Anzeige: Das REUSE-Badge kommt erst in die README, wenn `reuse lint` wirklich besteht.
