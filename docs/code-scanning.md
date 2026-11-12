# Code and dependency scanning

Every pull request and every night, CI runs these scanners. All of them are free. A finding fails
the check; it is fixed, or allowed in the scanner's own file with a reason (and an expiry date where
the tool supports one), so each exception shows up in review.

| Scanner | What it checks | Workflow | Configuration |
|---|---|---|---|
| CodeQL (`security-and-quality`) | Java and Kotlin code, workflows | `codeql.yml` | `codeql.yml` |
| Semgrep | Java, workflows, secrets (registry rule sets) | `code-scanners.yml` | rule sets in the workflow, `// nosemgrep: <rule>` with a reason |
| OSV-Scanner | known vulnerabilities of all Gradle dependencies (`gradle/verification-metadata.xml`) | `code-scanners.yml` | `osv-scanner.toml` |
| OWASP Dependency-Check | the same from the NVD, on the shipped jar | `code-scanners.yml` | needs `NVD_API_KEY` |
| SonarCloud | bugs, vulnerabilities, hotspots, smells, coverage, duplication | `code-scanners.yml` | `sonar-project.properties`, needs `SONAR_TOKEN` |
| Snyk | dependencies and code (Snyk Code) | `code-scanners.yml` | needs `SNYK_TOKEN` |
| Dependency review | new vulnerable or unlicensed dependencies in a pull request | `dependency-review.yml` | |
| Secrets, containers, SBOM | gitleaks, TruffleHog, Hadolint, Trivy, Syft, Grype | `secrets-and-containers.yml` | `.gitleaks.toml`, `.hadolint.yaml` |
| Workflows and infrastructure | actionlint, zizmor, Checkov, ZAP baseline | `static-checks.yml`, `zap-baseline.yml` | `.zap/rules.tsv` |

Results of CodeQL, Semgrep, OSV-Scanner, Trivy, Grype and Checkov appear under *Security, Code
scanning* of the repository.

## Steps only the owner can do

SonarCloud, Snyk and the NVD key need an account. Until the secret exists, the job ends green with
a notice in its log; it never claims a clean scan.

1. **SonarCloud.** Sign in at sonarcloud.io with GitHub, import `VectoDE/VectolynCloud` (free for
   public repositories), switch off *Automatic Analysis* (Administration, Analysis Method), create a
   token (My Account, Security) and store it as repository secret `SONAR_TOKEN`. If SonarCloud
   proposes another organisation or project key than the ones in `sonar-project.properties`,
   change the file. Set the quality gate under *Quality Gates*: on new code, coverage at least 80 %,
   duplication at most 3 %, all ratings A, no unreviewed security hotspots.
2. **Snyk.** Sign in at snyk.io with GitHub (free plan), copy the *Auth Token* from Account
   Settings and store it as repository secret `SNYK_TOKEN`.
3. **NVD API key.** Request a free key at nvd.nist.gov/developers/request-an-api-key (it arrives by
   e-mail) and store it as repository secret `NVD_API_KEY`.
4. After the secrets exist, run the workflow *Code and dependency scanners* once with *Run workflow*
   and check that the three jobs ran instead of printing a notice.

## Exceptions

`osv-scanner.toml` holds the one allowed advisory (commons-lang 2.6, test scope, no fixed release)
with an expiry date. See also [dependency-verification.md](dependency-verification.md).
