# Releasing

For maintainers. A release starts with a pushed tag; [`release.yml`](../../.github/workflows/release.yml) does the rest. The files, tag rules, image tags and the user side of checking a download are described in [Running VectolynCloud](../cloud/operations.md#what-a-release-contains). This page is the checklist and the map of what the workflow does.

## Checklist

1. **Cleanup check.** Run it as written in [OpenSSF Scorecard and the cleanup check](../openssf.md#the-cleanup-check): no open pull request, nothing fixable under *Security and quality*, and CI, CodeQL, Dependency Review and Scorecard have run on the newest commit of `main`.
2. **Docs match the code.** The status block in `README.md` and `README.de.md`, the wiki pages `Roadmap` and `Roadmap-DE`, and the reference pages in `docs/cloud/` describe what the release contains. Open work is marked as planned with its issue number.
3. **Pick the version.** Below 1.0.0 only `vMAJOR.MINOR.PATCH-dev` (optionally `-dev.N`). From 1.0.0: `-alpha`, `-beta`, `-rc` with an optional counter, `-lts` without a counter, or no suffix for a stable release ([rules](../cloud/operations.md#versions-and-tags)).
4. **Check the tag.**
   ```sh
   bash tools/release-banner/check-tag.sh v0.2.0-dev
   ```
5. **Create an annotated tag on the commit of `main`.** The banner takes its title from the first line of the tag message and its date from the tagger date.
   ```sh
   git tag -a -s v0.2.0-dev -m "Short title of the release"
   git push origin v0.2.0-dev
   ```
   Use `-a` without `-s` if you do not sign tags.
6. **Watch the run** of the *Release* workflow in the Actions tab. If the tag check rejects the tag, nothing was built; fix it and push a new tag (delete the old one first, see [operations](../cloud/operations.md#versions-and-tags)).
7. **Check the result** on the release page and with the commands in [Verifying a release](../cloud/operations.md#verifying-a-release): SHA-256, cosign bundle, GitHub attestation, and the image digest from the release notes.
8. **Close the loop.** Update the issues the release finishes (tick the tasks, close with a link to the release) and the status text in the README if it names the newest release.

## What the workflow does

| Job | Steps |
|---|---|
| Build, sign and attest | check the tag; `./gradlew build -Pversion=<tag without v>` (all tests); package `VectolynCloud.jar` and the two start script archives with `cloud/dist/package.sh`; draw the release banner; write `SHA256SUMS` for exactly five files; sign each file and `SHA256SUMS` with cosign (keyless, three attempts); create the build provenance with `actions/attest-build-provenance`; store the files as one artifact |
| Container image | check the jar against `SHA256SUMS`, build the image for `linux/amd64` and `linux/arm64` from the signed jar, push to `ghcr.io/vectode/vectolyn-cloud` with the version tag (plus `dev` for `-dev` tags and `latest` for stable and `-lts` tags), sign the image digest and attest its provenance |
| Publish GitHub release | create the release from the stored files with the banner, the image reference and a link to the verification steps at the top, followed by generated notes; pre-release flag from the tag |

Plugin jars are not part of a release. Operators build them from the repository.

## Rules worth knowing

- The tag sets the version of every module. There is no version file to edit.
- Every action in the workflow is pinned to a commit SHA and every job runs `step-security/harden-runner` with a list of allowed hosts. A new host in a release step has to be added in review ([OpenSSF](../openssf.md#what-is-in-place)).
- The packaging script and the banner also run as dry runs on every pull request ([`operations.yml`](../../.github/workflows/operations.yml)), so a broken release step shows up before the tag.
- Nothing is published by hand. If a run fails halfway, fix the cause and push a new tag; do not upload files to a release yourself, because the signatures and the provenance have to come from the workflow.

## Open items

The first complete release run with the image push, the egg under a real Wings and the managed MariaDB under the systemd hardening are tracked in [#11](https://github.com/VectoDE/VectolynCloud/issues/11) and [#62](https://github.com/VectoDE/VectolynCloud/issues/62). The update system (versioned migrations, self-update, maintenance mode, component updates) is planned in [#74](https://github.com/VectoDE/VectolynCloud/issues/74) to [#77](https://github.com/VectoDE/VectolynCloud/issues/77).
