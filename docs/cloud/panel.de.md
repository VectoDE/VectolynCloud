# Web-Panel

Modul `cloud/panel`. Eine Weboberfläche für Cloud und Netzwerk, die der Node selbst ausliefert: kein zweiter Prozess, kein Node.js zur Laufzeit, keine Dateien außerhalb des Jars. Die ausführliche englische Seite ist [panel.md](panel.md).

- **Server:** ein eingebetteter Netty-Server (HTTP/1.1) im Node (`PanelServer`) auf einem eigenen Port.
- **Oberfläche:** Next.js mit TypeScript und Tailwind in `cloud/panel/web/`, beim Build statisch exportiert und ins Jar gepackt.
- **Daten:** Das Panel führt die Routen der [REST-API](api.de.md) im selben Prozess aus. Jede Regel der API (Prüfung, `ETag`/`If-Match`, Fehler) gilt damit auch im Panel.
- **Sprachen:** Deutsch und Englisch. Beim ersten Besuch entscheidet die Sprache des Browsers, der Schalter in der Kopfzeile merkt sich die Wahl im Browser.

## Einschalten

Das Panel ist standardmäßig aus. Es braucht die API (`api.enabled: true` in `config/cloud.yml`):

```yaml
# config/panel.yml
schema-version: 1
enabled: true
bind: 127.0.0.1        # standardmäßig Loopback; jede andere Adresse braucht api.tls.enabled
port: 8090
# public-url: https://panel.example.org:8090
```

Änderungen gelten ab dem nächsten Start des Nodes. Danach `http://127.0.0.1:8090/` öffnen.

| Feld | Standard | Bedeutung |
|---|---|---|
| `enabled` | `false` | Panel mit dem Node starten |
| `bind` | `127.0.0.1` | Adresse, auf der es lauscht |
| `port` | `8090` | TCP-Port, 1 bis 65535 |
| `public-url` | keiner | die Adresse im Browser: `https://host[:port]`, auf Loopback auch `http://localhost[:port]`, ohne Pfad |

Jede Adresse außer Loopback braucht TLS. Das Panel nutzt dafür das Zertifikat der API (`api.tls.enabled`, siehe [API](api.de.md)). Ohne TLS startet es auf einer solchen Adresse nicht, und der Node schreibt den Grund ins Log.

Jede Anfrage muss das Panel im `Host`-Header nennen (`localhost`, `127.0.0.1`, `[::1]`, die Bind-Adresse oder den Host von `public-url`), sonst gibt es `421`. Das verhindert DNS-Rebinding gegen ein Panel auf Loopback.

## Was der Server sendet

- **Caching:** starkes `ETag` (SHA-256) für jede Datei, `304` bei passendem `If-None-Match`. Dateien unter `/_next/static/` haben den Hash im Namen und gelten ein Jahr (`immutable`), Seiten sind `no-cache`. Text geht gzip-komprimiert raus, JSON ist `no-store`.
- **Sicherheits-Header:** `nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, COOP und CORP `same-origin`, eine `Permissions-Policy` ohne Kamera, Mikrofon, Standort, Zahlung und USB (Passkeys nur für diese Origin), mit TLS HSTS.
- **CSP pro Seite:** nur die eigene Origin plus genau die Inline-Skripte der Seite über ihren SHA-256-Hash. Kein `unsafe-inline`, kein `unsafe-eval`. Der Server berechnet die Hashes beim Laden.
- **Grenzen:** 600 Anfragen pro Minute und Adresse (Burst 120), 256 Verbindungen, höchstens 32 je Adresse, Body 256 KiB, Leerlauf 120 s. Anfrageziele mit `%`, Backslash, leeren, `.`- oder `..`-Segmenten werden mit `400` abgelehnt.

## Konten und Anmeldung

**Erstes Konto:** Es gibt keinen Standard-Benutzer und kein Standard-Passwort. Solange kein Konto existiert, zeigt das Panel **Erstes Konto anlegen** und fragt nach dem einmaligen Einrichtungs-Token aus der Konsole des Knotens (`vcs_...`, einmal gültig, 24 Stunden; `api token setup` gibt einen neuen aus). Damit entsteht das Eigentümer-Konto mit allen Rechten; der Token ist danach verbraucht, auch für die API.

**Anmeldeverfahren:** Passwort (12 bis 256 Zeichen, PBKDF2-HMAC-SHA-256 mit 600.000 Iterationen), Authenticator-App (TOTP nach RFC 6238, jeder Code nur einmal), Passkeys (WebAuthn mit Benutzerprüfung; brauchen einen Hostnamen wie `localhost`, keine IP-Adresse) und Single Sign-on über OpenID Connect (Code-Flow mit PKCE, `state`, `nonce`; nur für Konten, die ihr Anbieter-Konto unter **Mein Konto** verknüpft haben). Einstellungen unter `oidc:` in `panel.yml`, das Client-Secret als `VECTOLYN_PANEL_OIDC_SECRET` in `secrets/vectolyn.env`; Details in [panel.md](panel.md#accounts-and-sign-in).

**Rollen und Rechte:** eingebaut sind `owner` (alles), `operator` und `viewer`; eigene Rollen entstehen aus den zehn API-Scopes und den Panel-Rechten `files:read`, `files:write`, `players:manage`, `audit:read`, `users:manage`. Niemand vergibt mehr Rechte, als er selbst hat; mindestens ein aktiver `owner` bleibt. Gespeichert in `secrets/panel-users.json` (nur für den Eigentümer lesbar, nur Hashes).

**Schutz:** Sitzungs-Cookie `__Host-vectolyn-session` (`HttpOnly`, `Secure`, `SameSite=Strict`), 30 Minuten Leerlauf, höchstens 12 Stunden. Jede ändernde Anfrage braucht den eigenen Origin und das CSRF-Token im Header `X-Vectolyn-CSRF`. Fünf Fehlversuche sperren ein Konto für 15 Minuten.

**Audit-Log:** `logs/panel-audit.jsonl`, eine Zeile je Anmeldung, Fehlversuch, Sperre, Abmeldung und Änderung an Konten, Faktoren und Rollen, ohne Passwörter oder Codes. Lesbar mit dem Recht `audit:read`.

## Oberfläche bauen

```sh
./gradlew build -Pvectolyn.panel.web=true
```

Braucht Node.js 20.9 oder neuer. Gradle führt `npm ci --ignore-scripts` (exakte Versionen aus `package-lock.json`, keine Installationsskripte) und `npm run build` aus und kopiert `web/out/` mit `files.index` ins Jar. Ohne die Eigenschaft enthält das Jar eine Platzhalterseite. Release-Builds und der Build-Job der CI unter Linux setzen sie.

## Barrierefreiheit

Ziel ist WCAG 2.2 AA, AAA wo erreichbar: Sprunglink zum Inhalt, sichtbarer Fokus (3 px), Ziele von mindestens 44 × 44 px, Seitensprache nach Wahl, Live-Regionen, keine Bewegung bei reduzierter Bewegung.

## Tests

`PanelServerTest`, `PanelConfigLoaderTest` und die Fuzz-Ziele in `PanelInputFuzzTest` (Anfrageziele, `panel.yml`, öffentliche URLs). Der Job **Panel frontend** in `ci.yml` prüft die Sprachkataloge, die Typen und den statischen Export.
