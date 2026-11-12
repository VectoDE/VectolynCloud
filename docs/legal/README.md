# Rechtstexte (Entwürfe) und Hinweise

**Stand: 2026-11-12. Alles in diesem Ordner ist ein Entwurf. Nichts davon ist anwaltlich oder steuerlich geprüft. Es ersetzt keine Rechts- oder Steuerberatung.**

## Anwalts- und Steuerberater-Hinweis

- Vor der ersten Veröffentlichung mit Echtbetrieb (öffentliches Netzwerk, Website, Shop) müssen **Datenschutzerklärung, Impressum und AGB von einer Rechtsanwältin oder einem Rechtsanwalt geprüft** werden. Das gilt auch für die Einwilligungstexte des Trackings (`docs/cloud/privacy.md`).
- Vor dem Live-Modus von Stripe oder Mollie ist **steuerlich zu klären** (Steuerberater): Umsatzsteuer auf digitale Güter, Ort der Leistung, Anmeldung beim One-Stop-Shop, Gewerbe- und Einkommensteuer, Buchführung der Zahlungsanbieter-Gebühren.
- Die Fragenlisten für die Anwältin oder den Anwalt stehen in [`docs/cloud/privacy.md`](../cloud/privacy.md) (Fragen 1 bis 13) und unten in diesem Ordner.
- Wer das Projekt selbst betreibt, ist selbst verantwortlich (Verantwortlicher nach Art. 4 Nr. 7 DSGVO, Diensteanbieter nach DDG). Das Projekt liefert Software und Vorlagen, keine Rechtsberatung.

## Inhalt

| Datei | Zweck | Status |
| --- | --- | --- |
| [`entwurf-datenschutzerklaerung.md`](entwurf-datenschutzerklaerung.md) | Entwurf, passend zu dem, was der Code tut | nicht geprüft, Platzhalter offen |
| [`entwurf-impressum.md`](entwurf-impressum.md) | Entwurf nach § 5 DDG | nicht geprüft, Platzhalter offen |
| [`entwurf-agb.md`](entwurf-agb.md) | Entwurf für den rein kosmetischen Shop | nicht geprüft, Platzhalter offen |
| [`zahlungsanbieter.md`](zahlungsanbieter.md) | Gebühren und Bedingungen von Stripe und Mollie, Live-Modus | Stand der Quellen 2026-11-12, nicht geprüft |

Platzhalter stehen in eckigen Klammern, zum Beispiel `[Name des Betreibers]`. Sie muss der Betreiber ausfüllen; das Projekt kennt diese Angaben nicht.

## Lizenzprüfung

Der Quelltext steht unter GPL-3.0-or-later. Die Lizenzen aller direkten Abhängigkeiten und ihre Vereinbarkeit stehen in [`docs/licenses/DEPENDENCY-LICENSES.md`](../licenses/DEPENDENCY-LICENSES.md), der REUSE-Stand in [`docs/licenses/REUSE.md`](../licenses/REUSE.md). Das Modul `cloud/privacy` bringt keine neue Abhängigkeit mit. Die Einschätzung zur GPL-Vereinbarkeit ist technisch; ob sie rechtlich trägt (zum Beispiel die Einbindung von PandaSpigot-API, GPL-3.0, nur zur Kompilierzeit) ist eine Frage für die Anwältin oder den Anwalt (Frage 14).

## Fragen für die Anwältin oder den Anwalt (Rechtstexte, Zahlungen, Lizenz)

14. Ist die Kombination aus GPL-3.0-or-later, den aufgeführten Abhängigkeiten und der nur zur Kompilierzeit genutzten PandaSpigot-API (GPL-3.0) lizenzrechtlich in Ordnung? Brauchen die Mojang-Bezüge (EULA, Markenhinweise) einen Hinweis?
15. Genügt der Entwurf des Impressums nach § 5 DDG für ein Netzwerk mit Website und Shop? Welche Angaben fehlen je nach Rechtsform?
16. Ist die Datenschutzerklärung vollständig nach Art. 13 und 14 DSGVO für: Sperrliste, Statistik, Tracking mit Einwilligung, Telemetrie, Zahlungsabwicklung, Server-Logs, Hosting, E-Mail?
17. Sind die AGB für den Verkauf digitaler, rein kosmetischer Inhalte wirksam (§§ 305 ff. BGB), insbesondere Widerrufsbelehrung und Erlöschen des Widerrufsrechts bei digitalen Inhalten (§ 356 Abs. 5 BGB), Gewährleistung für digitale Produkte (§§ 327 ff. BGB), Altersgrenzen und Minderjährige?
18. Sind Preisangaben, Button-Beschriftung („zahlungspflichtig bestellen“) und die Bestellübersicht rechtskonform (PAngV, § 312j BGB)?
19. Welche Pflichten entstehen aus der Zusammenarbeit mit Stripe und Mollie (Auftragsverarbeitung oder gemeinsame Verantwortung, Drittlandübermittlung, Hinweise in der Datenschutzerklärung)?
20. Was ist bei Verkauf an Verbraucher in anderen EU-Staaten zu beachten (Verbraucherrecht, Umsatzsteuer, One-Stop-Shop)? Dazu zusätzlich der Steuerberater.
