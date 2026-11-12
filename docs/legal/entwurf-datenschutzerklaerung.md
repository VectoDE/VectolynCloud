# Datenschutzerklärung (ENTWURF)

> **ENTWURF, nicht anwaltlich geprüft.** Dieser Text beschreibt, was die Software VectolynCloud technisch verarbeitet (Stand 2026-11-12, siehe [`docs/cloud/privacy.md`](../cloud/privacy.md)). Er ist keine Rechtsberatung und ersetzt keine Prüfung durch eine Rechtsanwältin oder einen Rechtsanwalt. Vor Veröffentlichung müssen alle Platzhalter in `[eckigen Klammern]` ausgefüllt und der Text geprüft werden. Abschnitte, die auf nicht eingeschaltete Funktionen verweisen, sind zu streichen, wenn der Betreiber sie nicht nutzt.

## 1. Verantwortlicher

[Name des Betreibers, Rechtsform]
[Anschrift]
E-Mail: [Kontaktadresse]
[Datenschutzbeauftragte/r, falls benannt]

## 2. Grundsatz

Wir verarbeiten nur die Daten, die für den Betrieb des Netzwerks nötig sind. Verfolgung von Spielaktivitäten und Telemetrie sind **standardmäßig ausgeschaltet**. Sie laufen nur, wenn der Betreiber sie einschaltet und, beim Tracking, du selbst im Spiel zustimmst.

## 3. Verbindung zum Netzwerk und Sicherheit (IP-Sperren)

- **Was:** Wird eine Spielerin oder ein Spieler gesperrt, speichert das Netzwerk die IP-Adresse, den Zeitpunkt, den Ablauf und den Sperrgrund, um die Sperre durchzusetzen.
- **Zweck und Rechtsgrundlage:** Schutz des Netzwerks und der Mitspielenden vor Missbrauch, berechtigtes Interesse, Art. 6 Abs. 1 Buchst. f DSGVO. Die Interessenabwägung dokumentiert der Betreiber: [Verweis].
- **Speicherdauer:** [90] Tage ab Anlage der Sperre, dann automatische Löschung (einstellbar von 1 bis 3650 Tagen). Eine erneute Sperre beginnt die Frist neu.
- **Empfänger:** keine; die Daten bleiben auf dem Server des Betreibers.

## 4. Statistik

- **Was:** Pro Spielmodus (Gruppe) werden Summen gespeichert: Spielzeit, Anzahl Anmeldungen, Spielerzahlen. Es werden **keine** Daten einzelner Personen gespeichert.
- **Zweck und Rechtsgrundlage:** Planung der Serverkapazität, berechtigtes Interesse, Art. 6 Abs. 1 Buchst. f DSGVO. Weil die Summen nicht auf Personen zurückführbar sind, sind sie voraussichtlich keine personenbezogenen Daten; das ist zu prüfen.
- **Speicherdauer:** solange der Betreiber die Statistik eingeschaltet lässt ([unbefristet / Frist]).

## 5. Aktivitätsdaten mit Einwilligung (Tracking)

Diese Funktion ist aus, solange der Betreiber sie nicht einschaltet und sich als Verantwortlichen im Hinweis nennt.

- **Einwilligung:** Du entscheidest im Spiel mit `/privacy on confirm`, nachdem du den Hinweis gelesen hast (`/privacy`). Ohne Zustimmung wird nichts über dich gespeichert.
- **Was:** [Liste aus dem Hinweistext: Besuche, Spielzeit, Spielmodus, Zeitpunkte; die genaue Liste steht im Hinweis der installierten Version (Version 1)].
- **Zweck:** [Zweck laut Hinweis].
- **Rechtsgrundlage:** Einwilligung, Art. 6 Abs. 1 Buchst. a DSGVO, § 25 Abs. 1 TDDDG, soweit auf Endeinrichtungen zugegriffen wird.
- **Speicherdauer:** [90] Tage nach deinem letzten Besuch, dann automatische Löschung.
- **Widerruf:** jederzeit mit `/privacy off`. Dann wird alles über dich sofort gelöscht. Der Widerruf berührt die Rechtmäßigkeit der bis dahin erfolgten Verarbeitung nicht.
- **Auskunft:** `/privacy data` zeigt alles, was über dich gespeichert ist. Der Betreiber kann dir die Daten auch über das Panel bereitstellen oder löschen.
- Der Nachweis deiner Einwilligung (Zeitpunkt, Textversion) wird für die Dauer der Speicherung aufbewahrt.

## 6. Telemetrie

Die Telemetrie ist **aus**. Schaltet der Betreiber sie ein, ist das nur mit dokumentierter Einwilligung und mit einem https-Ziel möglich. Übermittelt werden [Liste der Felder, z. B. Cloud-Version, Anzahl Knoten und Gruppen] an [Empfänger/Endpunkt]. Es werden keine Namen, IP-Adressen oder Spielerdaten gesendet. [Rechtsgrundlage und Speicherdauer des Empfängers eintragen.]

## 7. Website, Panel und Zugriffsprotokolle

[Hosting-Anbieter, Server-Logfiles (IP, Zeitpunkt, URL), Speicherdauer, Rechtsgrundlage Art. 6 Abs. 1 Buchst. f DSGVO. Nur ausfüllen, was tatsächlich zutrifft.]
[Cookies und ähnliche Technologien: nur notwendige; sonst Einwilligungsverwaltung beschreiben.]
[Analyse (z. B. Google Analytics 4 / Search Console): nur mit Einwilligung, Anbieter, Drittlandübermittlung.]

## 8. Zahlungen (nur wenn der Shop betrieben wird)

Zahlungen wickelt [Stripe Payments Europe, Ltd., Irland / Mollie B.V., Niederlande] ab. Wir erhalten Bestätigung, Betrag und eine Referenz; Zahlungsdaten (Karte, Konto) gibt du direkt beim Zahlungsanbieter ein. Rechtsgrundlage: Vertragserfüllung, Art. 6 Abs. 1 Buchst. b DSGVO. Aufbewahrung von Rechnungen: [Fristen nach HGB/AO, Steuerberater fragen]. Hinweise zu Drittlandübermittlungen: [vom Anwalt prüfen lassen].

## 9. Deine Rechte

Du hast das Recht auf Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung (Art. 18), Datenübertragbarkeit (Art. 20) und Widerspruch gegen Verarbeitung aus berechtigtem Interesse (Art. 21) sowie das Recht, erteilte Einwilligungen jederzeit zu widerrufen (Art. 7 Abs. 3). Du kannst dich bei einer Datenschutz-Aufsichtsbehörde beschweren, zum Beispiel bei der für den Betreiber zuständigen: [Behörde].

## 10. Änderungen

Stand: [Datum]. Ändern sich die Verarbeitungen, wird dieser Text angepasst. Bei Änderungen am Tracking-Hinweis ändert sich die Textversion und es ist erneut zuzustimmen.
