# Performance

[English](Performance)

**Es gibt noch keine Performance-Messungen.** Es wurde weder ein Benchmark noch ein Lasttest ausgeführt, deshalb enthält diese Seite keine Zahlen und wir behaupten keine. Sie nennt, was wir messen wollen, wie und wo die Berichte erscheinen, sobald es sie gibt.

## Ziele

Das Projekt setzt sich diese Ziele. Es sind Ziele, keine Ergebnisse. Jedes ist relativ zu einer Basislinie formuliert, die zuerst mit derselben Methode auf derselben Hardware gemessen wird ([#138](https://github.com/VectoDE/VectolynCloud/issues/138)).

| Messgröße | Ziel |
|---|---|
| Kaltstart bis die Cloud bereit ist | halb so lang wie die Basislinie |
| Speicher einer leeren Cloud ohne Dienste | halb so groß wie die Basislinie |
| Zeit vom Startbefehl bis ein Dienst erreichbar ist | halb so lang wie die Basislinie |
| Durchsatz und API-Latenz | doppelt so gut wie die Basislinie |
| Lasttest | mindestens 1.000 gleichzeitige Spieler und 200 Dienste |
| Live-Aktualisierung im Panel über WebSocket | unter einer Sekunde |

## Wie gemessen wird

1. Eine feste Messumgebung (Hardware, Java-Version, Konfiguration), zusammen mit den Ergebnissen festgehalten ([#138](https://github.com/VectoDE/VectolynCloud/issues/138)).
2. Ein Messaufbau und Benchmarks, die jeder wiederholen kann, mit Rohdaten und Skripten im Repository ([#139](https://github.com/VectoDE/VectolynCloud/issues/139)).
3. Optimierung von Startzeit und Speicher ([#140](https://github.com/VectoDE/VectolynCloud/issues/140)) sowie von Dienststart und Durchsatz ([#141](https://github.com/VectoDE/VectolynCloud/issues/141)).
4. Ein Lasttest mit [k6](https://k6.io/) gegen die API und den WebSocket, in der CI gegen eine Testumgebung ausgeführt, nie gegen ein System, das uns nicht gehört. Der Bericht ist ein CI-Artefakt und wird hier verlinkt ([#142](https://github.com/VectoDE/VectolynCloud/issues/142)).
5. Ein Abnahmelauf, der jedes Ziel gegen den Bericht prüft ([#143](https://github.com/VectoDE/VectolynCloud/issues/143)).

## Was es heute gibt

Das sind Tests des Verhaltens, nicht der Geschwindigkeit:

- Unit- und Integrationstests auf Linux und Windows mit JDK 25 sowie die Tests erneut auf Java 21;
- ein End-to-End-Test mit der echten `VectolynCloud.jar` und Fake-Servern in der CI und ein wöchentlicher Lauf mit echten Downloads und Servern;
- Fuzz-Tests für die Parser, die Eingaben von außen lesen;
- Grenzen, die den Node unter Last schützen: Verbindungs- und Anfragegrenzen des Cloud-Kanals und der API sowie ein Speicherbudget für die Autoskalierung ([Skalierung](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/scaling.de.md)).

Berichte werden von hier und aus der README verlinkt, sobald [#142](https://github.com/VectoDE/VectolynCloud/issues/142) und [#143](https://github.com/VectoDE/VectolynCloud/issues/143) erledigt sind.
