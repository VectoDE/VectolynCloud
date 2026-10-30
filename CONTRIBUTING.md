# Contributing to Vectolyn

[Deutsch](CONTRIBUTING.de.md)

Thanks for wanting to help. Vectolyn is at an early stage, so the rules below are short. They will grow with the code.

## Before you start

- Read the [README](README.md) and the [roadmap](https://github.com/users/VectoDE/projects/11).
- Questions and loose ideas go to [Discussions](https://github.com/VectoDE/VectolynCloud/discussions).
- For a bug or a concrete feature, open an issue with one of the forms. For anything bigger than a small fix, wait for a reply on the issue before you write code. That saves you work if the change does not fit the plan.
- Security problems: see [SECURITY.md](SECURITY.md). Never in a public issue.

## Workflow

1. Fork the repository and create a branch from `main` (`fix/end-portal-return`, `feat/lobby-navigator`).
2. Make small, focused commits.
3. Run `./gradlew build` before you push. To try a change in a running network, start it with `./gradlew :cloud:node:runLocal`; it runs in `run/`, which Git ignores.
4. Open a pull request against `main` and fill in the template. Link the issue with `Fixes #123`.
5. CI, CodeQL and dependency review have to pass, and every review conversation has to be resolved. A maintainer reviews and merges with squash. `main` takes no direct pushes or force pushes.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(lobby): add double jump
fix(proxy): keep Bedrock players on the lobby after restart
docs(wiki): describe the End server
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`. The PR title follows the same format, because it becomes the squash commit.

## Sign-off (DCO)

Every commit needs a `Signed-off-by` line. With it you confirm the [Developer Certificate of Origin](https://developercertificate.org/): you wrote the change or have the right to submit it under GPL-3.0.

```sh
git commit -s -m "fix(end): return players to their origin server"
```

Forgot it? `git commit --amend -s` for the last commit, `git rebase --signoff main` for several.

## Code

- Java, built with Gradle (Kotlin DSL). The toolchain is Java 25 and the bytecode target Java 21. CI builds and tests with JDK 25 and runs the tests again on Java 21 (`./gradlew test -PtestJava=21`). Tests that load Paper or Velocity classes carry `@Tag("requires-java25")`. Shared build settings live in the convention plugins under `build-logic/`.
- Code that needs Java 25 gets `@RequiresJava25`, a runtime check and a Java 21 path. See `docs/java-compatibility.md`.
- No formatter is configured yet. Match the surrounding code: 4 spaces, no wildcard imports, clear names over comments.
- New logic needs tests. Bug fixes get a test that fails without the fix where that is practical.
- Code that parses untrusted input (network frames, config files, downloads, API requests) also gets a Jazzer `@FuzzTest`. See [docs/cloud/architecture.md](docs/cloud/architecture.md#fuzz-tests).
- Every dependency and plugin is checked against SHA-256 checksums in `gradle/verification-metadata.xml`. After you add, remove or update one, run `bash gradle/update-verification-metadata.sh`, review the diff and commit it with your change. See [docs/dependency-verification.md](docs/dependency-verification.md).
- No new third-party plugins. The allowed base is listed in the README; everything else is written in this repository.
- Every game mode has to work for Bedrock players. Menus are inventory menus, no Bedrock forms.
- Never commit secrets, IPs, player data, worlds or server jars. `templates/` holds templates only; server runtimes live in `run/` (local development, ignored by Git) or in a cloud home outside the repository.

## Releases

A maintainer releases by pushing a tag, and the tag has to follow the [version rules](docs/cloud/operations.md#versions-and-tags): below 1.0.0 only `-dev` (`v0.2.0-dev`, `v0.2.0-dev.3`); from 1.0.0 also `-alpha`, `-beta` and `-rc` (each with an optional `.N`), `-lts` without a counter, or no suffix for a stable release. Any other tag stops the release before anything is built. Check a tag first with `bash tools/release-banner/check-tag.sh <tag>`.

## Languages

- Code, identifiers, commit messages and code comments: English.
- Everything players or admins read (messages, menus, README, wiki): English and German. Ingame texts follow the client language.
- Wiki pages live in `docs/wiki/`. English pages have no suffix, German pages end in `-DE` (`Architecture.md`, `Architecture-DE.md`). A workflow mirrors them into the GitHub wiki, so edit them here, not in the wiki.
- Issues and discussions: English or German, whatever you are comfortable with.

## License

By contributing you agree that your contribution is licensed under [GPL-3.0](LICENSE).
