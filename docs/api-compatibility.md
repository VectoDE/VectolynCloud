# API compatibility

[Deutsch](api-compatibility.de.md)

`common/protocol` (the messages of the cloud channel) and `common/client` (the client that bridges and plugins use) are the public API of VectolynCloud. Code built against one release must keep working with the next one unless the version says otherwise. CI checks this on every pull request.

## The rule

The version follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html):

| Baseline (newest release) | Incompatible API change allowed when the version being built is | Example |
|---|---|---|
| 1.0.0 or later | a new major version | `v1.4.2` to `2.0.0` |
| below 1.0.0 | a new minor version, or 1.0.0 | `v0.1.1-dev` to `0.2.0` |

SemVer itself allows anything below 1.0.0 (item 4). The project is stricter, so that a bridge built against 0.3.x keeps working with every 0.3.x: below 1.0.0 the minor version takes the place of the major. Compatible changes (a new class, a new method, a new message type) are always allowed.

"Incompatible" means binary or source incompatible for public and protected API, as japicmp reports it: a removed class, method or field, a changed signature or return type, a method added to an interface without a default, lower visibility, a class or method that became `final` or `abstract`, and so on.

## How it runs

Job `API compatibility` in [`ci.yml`](../.github/workflows/ci.yml):

1. The baseline is the newest tag `vX.Y.Z[-suffix]` in the history of the commit (`git tag --sort=-v:refname --merged HEAD`, with `-rc.1` sorted before the release). Without a tag there is nothing to compare and the job passes with a note.
2. The job checks the tag out next to the working tree and builds `:common:protocol:jar` and `:common:client:jar` there, with the build of that tag (its own Gradle version, dependency checksums and toolchain).
3. `./gradlew :tools:api-compat:apiCompat` compares both jars with the ones of the current commit ([`tools/api-compat`](../tools/api-compat)). The version being built is `version` in [`gradle.properties`](../gradle.properties); a `-SNAPSHOT` or other suffix is ignored.
4. The result table and the incompatible changes go into the job summary and into `tools/api-compat/build/reports/api-compat/report.md`.

The job fails when there is an incompatible change and the version is not a breaking step. The message names the version needed.

## When the check fails

Either make the change compatible (keep the old method and deprecate it, add a default method instead of an abstract one, add a new message type instead of changing one), or raise `version` in `gradle.properties` to the version the message names, for example `version=0.2.0-SNAPSHOT`, in the same pull request. The release that follows must then have at least that version; the tag check in `release.yml` does not know about this, so the person who tags the release takes the version from `gradle.properties`.

Locally, with the baseline jars in a folder as `protocol.jar` and `client.jar`:

```sh
git worktree add --detach ../baseline v0.1.1-dev
(cd ../baseline && ./gradlew :common:protocol:jar :common:client:jar)
mkdir -p ../baseline-jars
cp ../baseline/common/protocol/build/libs/*.jar ../baseline-jars/protocol.jar
cp ../baseline/common/client/build/libs/*.jar ../baseline-jars/client.jar
./gradlew :tools:api-compat:apiCompat -PapiBaselineDir=../baseline-jars -PapiBaselineTag=v0.1.1-dev
```

## The tool

[japicmp](https://github.com/siom79/japicmp) 0.27.0, Apache-2.0, as the command-line jar with its dependencies inside (`japicmp-0.27.0-jar-with-dependencies.jar`, one artifact in `gradle/verification-metadata.xml`, checksum compared with the one Maven Central publishes). It is a build tool only: it is neither on the classpath of a module nor in a jar that is shipped. `tools/api-compat` starts it in its own JVM and applies the version rule above; its tests run the real japicmp on small jars they compile themselves.japicmp's own option `--semantic-versioning` is not used: the rule for 0.x versions and the source of the versions are the project's own, so they live in `VersionRule` with their tests.

## Decision

Owner decision pending; the default above was set on 2026-11-12 (issue #130) and can be changed: the strict 0.x rule, the baseline from the newest tag built in CI (the releases carry no protocol and client jars), and the version from `gradle.properties`.
