# Security policy

_Deutsch: siehe [unten](#deutsch)._

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
**[Report a vulnerability](https://github.com/VectoDE/VectolynCloud/security/advisories/new)**
(repository tab *Security* → *Report a vulnerability*).

Do not open a public issue, discussion or pull request for a security problem.

Useful details:

- affected component (VectolynCloud, Vectolyn plugin, a server template, a workflow ...)
- version, tag or commit
- steps to reproduce or a proof of concept
- impact as you see it

## What happens next

Vectolyn is maintained by one person in their spare time. These are targets, not guarantees:

- first reply within 7 days
- an assessment (accepted, needs more info, not a vulnerability) within 14 days
- a fix or a mitigation as fast as the severity requires; we coordinate the publication date with you

Once fixed, we publish a GitHub Security Advisory and credit you, unless you prefer to stay anonymous.

## Supported versions

Until 1.0, only the latest release and the `main` branch receive security fixes.

| Version | Supported |
|---|---|
| `main` | yes |
| latest release | yes |
| older releases | no |

## Scope

In scope: code and configuration templates in this repository, the GitHub workflows and the release artifacts.

Out of scope: third-party software that Vectolyn runs (Paper, Velocity, Fabric, Geyser, Floodgate, ViaVersion and others). Report those to their projects. If Vectolyn's way of using them creates the problem, report it here.

Never test against servers you do not own or have no written permission to test.

## Verifying releases

A release contains `VectolynCloud.jar`, one archive with start scripts for Windows and one for Linux and macOS, `SHA256SUMS`, a Sigstore bundle per file (`*.sigstore.json`) and SLSA provenance (`*.intoto.jsonl`). The container image on GHCR is signed as well. Example:

```sh
sha256sum --check --ignore-missing SHA256SUMS
cosign verify-blob VectolynCloud.jar \
  --bundle VectolynCloud.jar.sigstore.json \
  --certificate-identity-regexp '^https://github[.]com/VectoDE/VectolynCloud/[.]github/workflows/release[.]yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify VectolynCloud.jar --repo VectoDE/VectolynCloud
```

All steps, including the image: [docs/cloud/operations.md](docs/cloud/operations.md#verifying-a-release).

---

## Deutsch

Sicherheitslücken bitte privat über GitHub melden:
**[Sicherheitslücke melden](https://github.com/VectoDE/VectolynCloud/security/advisories/new)**
(Reiter *Security* → *Report a vulnerability*). Bitte kein öffentliches Issue, keine Discussion und keinen Pull Request.

Hilfreich sind die betroffene Komponente, Version oder Commit, Schritte zum Nachstellen und deine Einschätzung der Auswirkung.

Ein Mensch pflegt Vectolyn in seiner Freizeit. Deshalb sind das Zielwerte, keine Zusagen: erste Antwort innerhalb von 7 Tagen, Einschätzung innerhalb von 14 Tagen, Behebung so schnell, wie es die Schwere verlangt. Den Veröffentlichungstermin stimmen wir mit dir ab. Nach der Behebung erscheint ein GitHub Security Advisory, auf Wunsch mit Nennung deines Namens.

Unterstützt werden `main` und das jeweils neueste Release.

Fehler in Fremdsoftware (Paper, Velocity, Geyser usw.) bitte direkt beim jeweiligen Projekt melden. Teste nur gegen Server, die dir gehören oder für die du eine schriftliche Freigabe hast.
