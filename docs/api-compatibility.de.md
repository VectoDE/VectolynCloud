# API-Kompatibilität

[English](api-compatibility.md)

`common/protocol` (die Nachrichten des Cloud-Kanals) und `common/client` (der Client, den Bridges und Plugins nutzen) sind die öffentliche API von VectolynCloud. Was gegen ein Release gebaut ist, muss mit dem nächsten weiterlaufen, solange die Version nichts anderes sagt. Die CI prüft das bei jedem Pull Request.

## Die Regel

Die Version folgt [Semantic Versioning 2.0.0](https://semver.org/lang/de/spec/v2.0.0.html):

| Basis (neuestes Release) | Inkompatible API-Änderung erlaubt, wenn die gebaute Version | Beispiel |
|---|---|---|
| ab 1.0.0 | eine neue Major-Version ist | `v1.4.2` auf `2.0.0` |
| unter 1.0.0 | eine neue Minor-Version oder 1.0.0 ist | `v0.1.1-dev` auf `0.2.0` |

SemVer selbst erlaubt unter 1.0.0 alles (Punkt 4). Das Projekt ist strenger, damit eine Bridge, die gegen 0.3.x gebaut ist, mit jedem 0.3.x läuft: Unter 1.0.0 übernimmt die Minor-Version die Rolle der Major-Version. Kompatible Änderungen (neue Klasse, neue Methode, neuer Nachrichtentyp) sind immer erlaubt.

„Inkompatibel" heißt binär- oder quellinkompatibel für öffentliche und geschützte API, so wie japicmp es meldet: entfernte Klasse, Methode oder Feld, geänderte Signatur oder Rückgabe, eine Interface-Methode ohne Default, geringere Sichtbarkeit, eine Klasse oder Methode, die `final` oder `abstract` wurde.

## Ablauf

Job `API compatibility` in [`ci.yml`](../.github/workflows/ci.yml):

1. Basis ist der neueste Tag `vX.Y.Z[-Suffix]` in der Historie des Commits (`-rc.1` vor dem Release einsortiert). Ohne Tag gibt es nichts zu vergleichen; der Job ist dann mit Hinweis grün.
2. Der Job checkt den Tag neben dem Arbeitsbaum aus und baut dort `:common:protocol:jar` und `:common:client:jar` mit dem Build dieses Tags.
3. `./gradlew :tools:api-compat:apiCompat` vergleicht beide Jars mit denen des aktuellen Commits ([`tools/api-compat`](../tools/api-compat)). Die gebaute Version ist `version` in [`gradle.properties`](../gradle.properties), ohne `-SNAPSHOT` oder anderen Suffix.
4. Ergebnistabelle und inkompatible Änderungen stehen in der Job-Zusammenfassung und in `tools/api-compat/build/reports/api-compat/report.md`.

Der Job schlägt fehl, wenn es eine inkompatible Änderung gibt und die Version kein brechender Schritt ist. Die Meldung nennt die nötige Version.

## Wenn die Prüfung fehlschlägt

Entweder die Änderung kompatibel machen (alte Methode behalten und als veraltet markieren, Default-Methode statt abstrakter, neuer Nachrichtentyp statt geändertem) oder `version` in `gradle.properties` im selben Pull Request auf die genannte Version setzen, zum Beispiel `version=0.2.0-SNAPSHOT`. Das folgende Release braucht dann mindestens diese Version; wer den Tag setzt, nimmt die Version aus `gradle.properties`.

Lokal: siehe die Befehle in der [englischen Fassung](api-compatibility.md#when-the-check-fails).

## Werkzeug

[japicmp](https://github.com/siom79/japicmp) 0.27.0, Apache-2.0, als Kommandozeilen-Jar mit eingebetteten Abhängigkeiten (ein Artefakt in `gradle/verification-metadata.xml`, Prüfsumme mit der von Maven Central abgeglichen). Nur Build-Werkzeug: weder auf dem Klassenpfad eines Moduls noch in einem ausgelieferten Jar. `tools/api-compat` startet es in einer eigenen JVM und wendet die Regel oben an; die Tests lassen das echte japicmp auf selbst kompilierten kleinen Jars laufen.

## Entscheidung

Standard vom 2026-11-12 (Issue #130), vom Inhaber änderbar: die strenge 0.x-Regel, die Basis aus dem neuesten Tag, in der CI gebaut (die Releases enthalten keine Protocol- und Client-Jars), und die Version aus `gradle.properties`.
