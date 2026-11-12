# Stripe und Mollie: Live-Modus, Gebühren, Bedingungen

**Stand der Quellen: 2026-11-12. Nicht rechtlich oder steuerlich geprüft. Preise und Bedingungen ändern sich; vor dem Live-Gang auf den Seiten der Anbieter erneut nachsehen.**

Vorgabe des Projekts: Stripe läuft nur im Testmodus, bis der Inhaber den Live-Modus ausdrücklich freigibt. Der Shop (kosmetisch, ohne Vorteile im Spiel) ist erst geplant; diese Seite bereitet die Entscheidung vor.

## Gebühren (Deutschland, Standardtarife)

| Posten | Stripe ([stripe.com/de/pricing](https://stripe.com/de/pricing)) | Mollie ([mollie.com/de/pricing](https://www.mollie.com/de/pricing)) |
| --- | --- | --- |
| Karten aus dem EWR (Standard) | 1,5 % + 0,25 € | 1,80 % + 0,25 € (Verbraucherkarten) |
| Karten aus dem EWR (Premium/Firmenkarten) | 2,8 % + 0,25 € | 2,90 % + 0,25 € |
| Karten aus dem Vereinigten Königreich | 2,5 % + 0,25 € | als Nicht-EWR-Karte: 3,25 % + 0,25 € |
| Internationale Karten | 3,15 % + 0,25 € | 3,25 % + 0,25 € |
| Währungsumrechnung | +2 % bei Umrechnung (UK und internationale Karten) | nicht auf der Seite genannt |
| SEPA-Lastschrift | 0,35 € | 0,35 € |
| Klarna | ab 2,99 % + 0,35 € | 2,99 % + 0,35 € |
| PayPal | auf der Seite nicht genannt | PayPal-Gebühren + 0,10 € |
| Rückbuchung (Dispute) | 20,00 € je Fall (bei Erfolg des Widerspruchs erstattet, bei Verlust nicht) | auf der Seite nicht genannt |
| Monatsgrundgebühr | auf der Seite nicht genannt | 0 € (Pro: 20 € ab 5.000 € Monatsvolumen) |
| Auszahlungen | auf der Seite nicht genannt | bis 5 pro Monat frei, danach 0,25 € (für Unternehmen unter 500.000 € Jahresvolumen) |
| Erstattung | Gebühren der Ursprungszahlung werden nicht erstattet | nicht auf der Seite genannt |

Rechenbeispiel: Ein Kauf über 5,00 € mit einer EWR-Standardkarte kostet bei Stripe 0,325 € (6,5 %), bei Mollie 0,34 € (6,8 %). Bei kleinen Beträgen dominiert die Fixgebühr; das ist für Preise und eine Mindestbestellsumme zu bedenken.

Zahlungsmethoden und Gebühren sind getrennt von der Umsatzsteuer zu buchen (Steuerberater).

## Bedingungen, die für den Live-Modus wichtig sind

**Stripe** (Services Agreement, [stripe.com/de/legal/ssa](https://stripe.com/de/legal/ssa), gelesen bis Zeichen 100 000 von 143 961):

- Vertragspartner für Unternehmen im EWR ist Stripe Payments Europe, Limited (Irland); irisches Recht.
- Stripe darf Angaben zur Person und zum Unternehmen verlangen (KYC); fehlende Angaben sind ein Grund für eine Sperre (Abschnitt 10.1).
- Stripe darf eine Sicherheitsleistung (Reserve) einbehalten und Forderungen gegen Guthaben und hinterlegte Konten aufrechnen (Abschnitte 7.2, 12).
- Stripe darf jederzeit ordentlich kündigen und bei wichtigem Grund sofort (Abschnitt 10.1).
- Für verbotene oder eingeschränkte Geschäfte braucht man vorher Stripes schriftliche Zustimmung ([Liste](https://stripe.com/de/legal/restricted-businesses)). Ob virtuelle Spielgegenstände dazugehören, ist vor dem Live-Gang gegen diese Liste zu prüfen.
- Nicht gelesen: der Rest des Textes und die Dienstbedingungen für Zahlungen (Rückbuchungshaftung). Das ist nachzuholen.

**Mollie** (User Agreement, Stand „Updating our User Agreement 2026“, [Hilfeartikel](https://help.mollie.com/hc/en-gb/articles/38518105024146-Updating-our-User-Agreement-2026)):

- Nach der Suche in den öffentlichen Quellen: Mollie verlangt vor Zahlungen und Auszahlungen eine Unternehmensprüfung (KYB: Identität, Eigentümerstruktur, Tätigkeit).
- Die Bedingungen von 2026 fassen Rolling Reserve und Einbehalt von Geldern in einem Artikel „Reserves“ zusammen; die frühere „Reserve Balance“ heißt Refund Reserve und dient nur Erstattungen. Aufrechnung ist ein eigener Artikel (5.5).
- **Nicht geprüft:** der vollständige Wortlaut (Kündigung, Auszahlungsfristen, Verbotene Geschäfte). Die Seite `mollie.com/de/legal/terms-and-conditions` lieferte 404; das aktuelle deutsche User Agreement ist von Hand auf mollie.com zu holen und zu lesen.

## Was vor dem Live-Modus zu erledigen ist (Inhaber)

- [ ] Unternehmensdaten, Bankkonto und Ausweisdaten beim gewählten Anbieter hinterlegen (KYC/KYB). Macht der Inhaber selbst.
- [ ] Verbotene-Geschäfte-Liste des Anbieters gegen den Shop-Inhalt prüfen.
- [ ] AGB, Widerrufsbelehrung, Impressum und Datenschutzerklärung von der Anwältin oder dem Anwalt prüfen lassen ([README](README.md)).
- [ ] Steuerliche Behandlung mit dem Steuerberater klären (Umsatzsteuer digitale Güter, OSS, Buchung der Gebühren).
- [ ] Mit Testkarten alle Abläufe prüfen (Zahlung, Erstattung, Rückbuchung, Webhook); Stripe bleibt bis zur Freigabe im Testmodus.
- [ ] Webhook-Geheimnisse und Schlüssel nur in `secrets/vectolyn.env`, nie im Code.
