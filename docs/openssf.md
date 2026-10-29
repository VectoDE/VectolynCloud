# OpenSSF Scorecard and the cleanup check

[Deutsch](openssf.de.md)

The repository runs [OpenSSF Scorecard](https://scorecard.dev/viewer/?uri=github.com/VectoDE/VectoraCloud) on every push to `main`, weekly and on demand ([`scorecard.yml`](../.github/workflows/scorecard.yml)). Its findings show up under *Security and quality* next to CodeQL. The project also holds the [OpenSSF Best Practices badge](https://www.bestpractices.dev/projects/16026).

## The cleanup check

Run at the end of every stage and before every release tag. A stage is finished and a tag may be pushed only when all of this is true:

1. No pull request is open. A Dependabot pull request is either merged together with the code changes it needs, or closed with a comment that says why.
2. *Security and quality* lists no open CodeQL, Scorecard, Dependabot or secret scanning alert that can be fixed. Findings are fixed in code, workflows, settings or files. Nothing is dismissed by hand and nothing is suppressed in the code.
3. CI, CodeQL, Dependency Review and Scorecard ran on the newest commit of `main` after the last merge. If a run brings up something new, it is fixed and the runs repeat until nothing fixable is left.
4. What stays open is listed below with its reason.

The first round of this check was [issue #71](https://github.com/VectoDE/VectoraCloud/issues/71).

## What is in place

- **Pinned-Dependencies:** every action is pinned to a commit SHA, every image (Dockerfile, compose file, actionlint) to a digest. The release builds its provenance with `actions/attest-build-provenance` instead of slsa-github-generator, because the generator can only be referenced by tag ([operations.md](cloud/operations.md#verifying-a-release)).
- **Binary-Artifacts:** the only binary is `gradle/wrapper/gradle-wrapper.jar`. [`gradle-wrapper.yml`](../.github/workflows/gradle-wrapper.yml) checks it against the checksums Gradle publishes, and `distributionSha256Sum` pins the distribution. Scorecard accepts the jar only if that workflow has a successful run on the newest commit of `main` when Scorecard looks, so `scorecard.yml` waits for it.
- **Fuzzing:** Jazzer fuzz tests in the modules, replayed in every test run and fuzzed nightly ([`fuzz-nightly.yml`](../.github/workflows/fuzz-nightly.yml)).
- **Dependency verification:** every Gradle dependency is checked against a SHA-256 checksum, and CI fails if a pull request weakens that ([dependency-verification.md](dependency-verification.md)).
- **Egress:** every job runs `step-security/harden-runner` with `egress-policy: block` and its own list of allowed hosts. A job that needs a new host fails until the host is added in review. On Windows the harden-runner agent only becomes active about a minute into a job and then cuts off downloads that are already running, so the Windows jobs only audit.

## What cannot reach 10 for now

| Check | Why | Changes when |
|---|---|---|
| Maintained | Scorecard gives 0 to every repository younger than 90 days, whatever the activity. The repository was created on 2026-10-28. | on its own, 90 days after creation |
| Code-Review | Scorecard counts merged changes with an approving review by someone other than the author. There is one maintainer, and GitHub does not let authors approve their own pull requests, so the count stays at 0. Every change still goes through a pull request with all required checks. | a second maintainer reviews |
| Branch-Protection | The `main` ruleset requires pull requests (squash only), linear history, strict required status checks and resolved review threads, and blocks deletion and force pushes, with no bypass. Scorecard also wants at least one required approval, code-owner review and approval of the last push. Each of those needs a second person; with one maintainer they would block every merge. | a second maintainer joins; then raise the ruleset |
| Contributors | Counts contributors from different organisations. | more contributors |
| CII-Best-Practices | Passing badge. Silver needs, among other things, a second person with commit rights and reviews of other people's changes. | together with Code-Review |
