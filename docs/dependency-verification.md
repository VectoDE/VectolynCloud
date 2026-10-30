# Dependency verification

[Deutsch](dependency-verification.de.md)

Every Gradle run checks each dependency and plugin it resolves against the SHA-256 checksums in [`gradle/verification-metadata.xml`](../gradle/verification-metadata.xml). That includes the `.pom` and `.module` files. If a checksum is missing or does not match, the build stops with `Dependency verification failed`. This applies on every machine, in every CI job and in the Docker image build.

## What it covers

The file lists every artifact of every resolvable configuration:

- the main build: compile, runtime and test classpaths of all modules, the Jazzer fuzz setup, the `e2eTest`/`e2eLiveTest`/`mariadbTest`/`networkTest` tasks, the classpaths of the CodeQL and release builds
- the `compileOnly` server APIs of the bridges and plugins: paper-api and velocity-api from PaperMC, pandaspigot-api from repo.hpfxd.com
- `build-logic` (the Kotlin DSL plugin and its Kotlin toolchain) and the settings plugin `foojay-resolver-convention`

`gradle.properties` sets `org.gradle.dependency.verification=strict`. Strict is Gradle's default anyway; the line is there so a switch to `lenient` or `off` shows up in review.

Not covered, because other checks handle these:

- The Gradle distribution: `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` and the wrapper validation in CI.
- JDKs the Foojay resolver downloads for a toolchain. That is toolchain provisioning, not dependency resolution. CI installs its JDKs with `actions/setup-java`.
- Server software the cloud downloads at runtime. It has its own checksums, see [docs/cloud/download.md](cloud/download.md).
- `-sources.jar` and `-javadoc.jar`, which only IDEs download. They are never on a classpath and are trusted, so that "Download sources" in the IDE keeps working.

## Updating the metadata

After any change to dependencies, plugins or repositories:

```sh
bash gradle/update-verification-metadata.sh
git diff -- gradle/verification-metadata.xml
```

On Windows, run it in Git Bash. The script keeps the `<configuration>` block, drops all entries and lets Gradle write them again (`./gradlew --write-verification-metadata sha256 help`). With this flag the `help` task resolves every configuration that exists after configuration, and `--refresh-dependencies` makes Gradle read the parent and BOM `.pom` files again, which a warm local cache would otherwise skip. Entries of removed dependencies disappear. A configuration that a plugin only creates while a task runs is not covered; CI would then fail with a missing checksum, and that dependency has to be added by running the task in question with `--write-verification-metadata sha256`. Commit the file in the same pull request as the dependency change.

Do not edit checksums by hand and do not run `--write-verification-metadata` alone: the script also contains the PandaSpigot workaround described below.

### Review checklist

Every entry is trust on first use: the script records what the repositories serve at that moment. The review is what makes it a check.

1. Only the components you expect have changed. A version bump replaces the entries of that version; new entries should be explainable by new transitive dependencies.
2. New groups or a new repository get a closer look: who publishes it, why it is needed.
3. For artifacts from outside Maven Central (PaperMC, repo.hpfxd.com) compare at least the main jar with the checksum the repository publishes:

   ```sh
   curl -fsS https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/<version>/paper-api-<version>.jar.sha256
   ```

   The value must equal the `sha256` of that artifact in the file.

## Dependabot

Dependabot updates `gradle/libs.versions.toml` and the build files, but not `gradle/verification-metadata.xml`. Its pull requests therefore fail at first. The workflow [`dependabot-verification-metadata.yml`](../.github/workflows/dependabot-verification-metadata.yml) fixes that:

1. Dependabot opens or updates a pull request that touches `gradle/**`, a `*.gradle.kts` file or `gradle.properties`.
2. Job `regenerate` checks out the Dependabot commit and runs `gradle/update-verification-metadata.sh`. It has a read-only token and no stored credentials. If anything other than the metadata file changes, it fails.
3. Job `commit` (only token with `contents: write`, runs no Gradle and no build code) checks the artifact with [`.github/scripts/check-verification-metadata.py`](../.github/scripts/check-verification-metadata.py): one plain metadata file, only `<component>`/`<artifact>`/`<sha256>` entries, an unchanged `<configuration>` block, and no changed checksum for an artifact that is already in the file. A version bump can add entries, never replace one. Then it commits the file with sign-off as `github-actions[bot]` and pushes it to the Dependabot branch. If Dependabot moved the branch in the meantime, the push fails and the next run takes over.
4. A push with `GITHUB_TOKEN` does not start other workflows. A maintainer reviews the commit using the checklist above and then pushes an empty commit to the branch, which runs CI, CodeQL and dependency review on the new head:

   ```sh
   gh pr checkout <n>
   git commit --allow-empty -s -m "ci: run checks"
   git push
   ```

   Do not close and reopen the pull request for this: on close, Dependabot treats the update as declined.

The workflow uses the plain `pull_request` event, never `pull_request_target`. It only runs when Dependabot opened the pull request from a branch of this repository and Dependabot triggered the run. Pull request data reaches the shell only through environment variables. All actions are pinned by commit SHA.

Once a branch has commits from others, Dependabot no longer rebases it on its own. `@dependabot recreate` builds the branch again; the workflow then writes the metadata again. Without the workflow, the manual way is the same: check out the Dependabot branch, run the script, review, commit with `-s`, push.

## Why SHA-256 and no PGP signatures

Gradle can also check PGP signatures and keep the public keys in the repository. We tried it on 2026-10-29 (`--write-verification-metadata pgp,sha256 --export-keys`) and decided against it for now:

- **The riskiest artifacts are unsigned.** paper-api, velocity-api, brigadier (Mojang), the md-5 artifacts, pandaspigot-api and the Gradle plugin marker files have no signatures. These come from repositories outside Maven Central, one of them run by a single person. They could only ever get checksums.
- **Many keys cannot be fetched.** 13 signing keys were on neither keys.openpgp.org nor keyserver.ubuntu.com. Their 84 artifacts, among them Netty, JLine, Jazzer, SLF4J and parts of Adventure, fell back to checksums as well. Of the libraries VectoraCloud ships, only Jackson, the MariaDB driver and SnakeYAML would have been checked by signature.
- **Trusted keys would be trust on first use too.** Gradle trusts whatever key it downloads and widens the trust to whole groups (one key for all of `com.google.*`, for example). It only becomes stronger than checksums once every fingerprint is checked against the project's own published key, for more than 40 keys.
- **Fewer things to see in review.** With a trusted key, a version bump changes nothing in the file. With checksums, every new artifact shows up in the diff.
- **Network dependence.** Writing the file would depend on key servers in every Dependabot run.

PGP would add one real protection: against an account takeover where someone publishes a new version signed with a different key. We will look at it again when Netty and JLine keys can be fetched and PaperMC signs its artifacts. Until then the protection rests on reviewing the checksums.

## PandaSpigot workaround

pandaspigot-api is only published as a snapshot, so the build pins one unique snapshot (`1.8.8-R0.1-20251003.170525-12`). Gradle 9.8.2 fails when writing metadata for such a version (`Multiple entries with same key`) as soon as metadata verification is on. Reading works. The script therefore trusts the `.pom` and `.module` of pandaspigot-api during the write and then replaces that entry with their SHA-256 from the Gradle cache. In the committed file they are checked like everything else; CI fails if the temporary entry is left over.

## Vulnerable transitive versions

OpenSSF Scorecard (check *Vulnerabilities*) reads the artifacts in `gradle/verification-metadata.xml`, so a version that only a server API pulls in as a compile-only or test-only dependency still counts. `vectora.java-conventions` raises these libraries to a patched release in every configuration when an API requests an older one: Guava 33.7.2, Gson 2.14.0, commons-lang3 3.18.0, log4j-api 2.26.1, plexus-utils 3.6.1. The rule only raises, never lowers. Remove an entry once the APIs ship a fixed version themselves.

Check locally with `osv-scanner scan source -L gradle/verification-metadata.xml`. Two findings stay:

- **commons-lang 2.6** (GHSA-j288-q9x7-2f5v): pandaspigot-api needs it at test runtime, because the Bukkit 1.8.8 classes load it. The 2.x line has no fixed release. The legacy bridge only uses it in tests, it is not part of any shipped jar.
- **kotlin-gradle-plugin 2.4.10** (GHSA-r937-wjx7-w2jp): comes with the `kotlin-dsl` plugin of the Gradle version, which builds `build-logic`. The advisory concerns the Kotlin build cache, which this build does not use. The fix exists only as 2.4.20-Beta1; it arrives with a Gradle release that embeds it.

## When verification fails

- **You changed dependencies:** run the script, review, commit.
- **You changed nothing:** do not regenerate. Find out which artifact failed. A damaged local cache is fixed with `./gradlew build --refresh-dependencies`. If the error stays, the repository serves different bytes than before, which is a reason to open a [security report](../SECURITY.md).
- Gradle writes a report to `build/reports/dependency-verification/`.

## CI

- The job `Validate Gradle wrapper` ([`gradle-wrapper.yml`](../.github/workflows/gradle-wrapper.yml)) checks that the file exists, verifies metadata and has SHA-256 entries; that it has no XML comments, no PGP keys, no leftover workaround entry and no trusted artifacts besides `-javadoc.jar`/`-sources.jar`; that `gradle.properties` sets `strict`; and that no workflow, Gradle build file, properties file or Dockerfile switches verification off or rewrites the file (long options, `-F`/`-M`, the property in any syntax).
- The same job runs `.github/scripts/check-verification-metadata.py` on the file: only plain SHA-256 entries, no PGP entries, `<also-trust>`, DOCTYPE or duplicates.
- It then compares the file with the base branch, using the checker from the base commit so a pull request cannot loosen the rules it is checked against. The `<configuration>` block must be unchanged, and no checksum of an artifact listed on both sides may change. New entries are fine. Removed entries are fine too: Gradle refuses an artifact without a checksum, so a missing entry fails the build instead of letting a file through. A change to the checker or to `<configuration>` therefore needs two pull requests: the checker first, then the file.
- The Dependabot workflow only accepts a regenerated file whose `<configuration>` block is unchanged and in which no existing checksum changed.
- Every other job runs Gradle with verification on, so a missing or wrong checksum fails `Build`, `Tests on Java 21`, `Integration`, CodeQL and the release.
- Changes under `gradle/`, `build-logic/`, `settings.gradle.kts` or `gradle.properties` also start the `Integration` job.
