# Security policy

_Deutsch: siehe [unten](#deutsch)._

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
**[Report a vulnerability](https://github.com/VectoDE/Vectora-Network/security/advisories/new)**
(repository tab *Security* → *Report a vulnerability*).

Do not open a public issue, discussion or pull request for a security problem.

Useful details:

- affected component (VectoraCloud, Vectora plugin, a server template, a workflow ...)
- version, tag or commit
- steps to reproduce or a proof of concept
- impact as you see it

## What happens next

Vectora is maintained by one person in their spare time. These are targets, not guarantees:

- first reply within 7 days
- an assessment (accepted, needs more info, not a vulnerability) within 14 days
- a fix or a mitigation as fast as the severity requires; we coordinate the publication date with you

Once fixed, we publish a GitHub Security Advisory and credit you, unless you prefer to stay anonymous.

## Supported versions

There is no release yet. Until 1.0, only the latest release and the `main` branch receive security fixes.

| Version | Supported |
|---|---|
| `main` | yes |
| latest release | yes |
| older releases | no |

## Scope

In scope: code and configuration templates in this repository, the GitHub workflows and the release artifacts.

Out of scope: third-party software that Vectora runs (Paper, Velocity, Fabric, Geyser, Floodgate, ViaVersion and others). Report those to their projects. If Vectora's way of using them creates the problem, report it here.

Never test against servers you do not own or have no written permission to test.

## Verifying releases

Release artifacts come with `SHA256SUMS`, a Sigstore bundle per file (`*.sigstore.json`) and SLSA provenance (`*.intoto.jsonl`). Example:

```sh
sha256sum -c SHA256SUMS
cosign verify-blob Vectora-Paper.jar \
  --bundle Vectora-Paper.jar.sigstore.json \
  --certificate-identity-regexp '^https://github.com/VectoDE/Vectora-Network/.github/workflows/release.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify Vectora-Paper.jar --repo VectoDE/Vectora-Network
```

---

## Deutsch

Sicherheitslücken bitte privat über GitHub melden:
**[Sicherheitslücke melden](https://github.com/VectoDE/Vectora-Network/security/advisories/new)**
(Reiter *Security* → *Report a vulnerability*). Bitte kein öffentliches Issue, keine Discussion und keinen Pull Request.

Hilfreich sind die betroffene Komponente, Version oder Commit, Schritte zum Nachstellen und deine Einschätzung der Auswirkung.

Ein Mensch pflegt Vectora in seiner Freizeit. Deshalb sind das Zielwerte, keine Zusagen: erste Antwort innerhalb von 7 Tagen, Einschätzung innerhalb von 14 Tagen, Behebung so schnell, wie es die Schwere verlangt. Den Veröffentlichungstermin stimmen wir mit dir ab. Nach der Behebung erscheint ein GitHub Security Advisory, auf Wunsch mit Nennung deines Namens.

Unterstützt werden `main` und das jeweils neueste Release. Ein Release gibt es noch nicht.

Fehler in Fremdsoftware (Paper, Velocity, Geyser usw.) bitte direkt beim jeweiligen Projekt melden. Teste nur gegen Server, die dir gehören oder für die du eine schriftliche Freigabe hast.
