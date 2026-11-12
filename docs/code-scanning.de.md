# Code- und Abhängigkeits-Scanner

Bei jedem Pull Request und jede Nacht laufen diese Scanner in der CI. Alle sind kostenlos. Ein
Fund lässt die Prüfung fehlschlagen. Er wird behoben oder in der Datei des Scanners mit Begründung
(und Ablaufdatum, wo das Werkzeug es kennt) zugelassen, damit jede Ausnahme im Review sichtbar ist.

| Scanner | Prüft | Workflow | Konfiguration |
|---|---|---|---|
| CodeQL (`security-and-quality`) | Java- und Kotlin-Code, Workflows | `codeql.yml` | `codeql.yml` |
| Semgrep | Java, Workflows, Secrets (Registry-Regelsätze) | `code-scanners.yml` | Regelsätze im Workflow, `// nosemgrep: <Regel>` mit Begründung |
| OSV-Scanner | bekannte Schwachstellen aller Gradle-Abhängigkeiten (`gradle/verification-metadata.xml`) | `code-scanners.yml` | `osv-scanner.toml` |
| OWASP Dependency-Check | dasselbe aus Sicht der NVD, am ausgelieferten Jar | `code-scanners.yml` | braucht `NVD_API_KEY` |
| SonarCloud | Bugs, Schwachstellen, Hotspots, Code Smells, Abdeckung, Duplikate | `code-scanners.yml` | `sonar-project.properties`, braucht `SONAR_TOKEN` |
| Snyk | Abhängigkeiten und Code (Snyk Code) | `code-scanners.yml` | braucht `SNYK_TOKEN` |
| Dependency Review | neue verwundbare oder unlizenzierte Abhängigkeiten im Pull Request | `dependency-review.yml` | |
| Secrets, Container, SBOM | gitleaks, TruffleHog, Hadolint, Trivy, Syft, Grype | `secrets-and-containers.yml` | `.gitleaks.toml`, `.hadolint.yaml` |
| Workflows und Infrastruktur | actionlint, zizmor, Checkov, ZAP Baseline | `static-checks.yml`, `zap-baseline.yml` | `.zap/rules.tsv` |

Die Ergebnisse von CodeQL, Semgrep, OSV-Scanner, Trivy, Grype und Checkov stehen im Repository
unter *Security, Code scanning*.

## Schritte, die nur der Inhaber machen kann

SonarCloud, Snyk und der NVD-Schlüssel brauchen ein Konto. Solange das Secret fehlt, endet der Job
grün mit einem Hinweis im Log. Er meldet nie einen sauberen Scan.

1. **SonarCloud.** Auf sonarcloud.io mit GitHub anmelden, `VectoDE/VectolynCloud` importieren
   (für öffentliche Repositories kostenlos), *Automatic Analysis* ausschalten (Administration,
   Analysis Method), unter My Account, Security ein Token erzeugen und als Repository-Secret
   `SONAR_TOKEN` speichern. Schlägt SonarCloud eine andere Organisation oder einen anderen
   Projektschlüssel vor als in `sonar-project.properties`, die Datei anpassen. Das Quality Gate
   unter *Quality Gates* setzen: auf neuem Code Abdeckung mindestens 80 %, Duplikate höchstens 3 %,
   alle Bewertungen A, keine ungeprüften Security Hotspots.
2. **Snyk.** Auf snyk.io mit GitHub anmelden (kostenloser Tarif), in den Account Settings den
   *Auth Token* kopieren und als Repository-Secret `SNYK_TOKEN` speichern.
3. **NVD-API-Schlüssel.** Auf nvd.nist.gov/developers/request-an-api-key einen kostenlosen
   Schlüssel anfordern (er kommt per E-Mail) und als Repository-Secret `NVD_API_KEY` speichern.
4. Wenn die Secrets existieren, den Workflow *Code and dependency scanners* einmal mit *Run workflow*
   starten und prüfen, dass die drei Jobs gelaufen sind und keinen Hinweis ausgeben.

## Ausnahmen

`osv-scanner.toml` enthält die eine zugelassene Meldung (commons-lang 2.6, Testumfang, keine
behobene Version) mit Ablaufdatum. Siehe auch [dependency-verification.de.md](dependency-verification.de.md).
