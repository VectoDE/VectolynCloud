# Developer documentation

For people who change VectolynCloud. Users and admins start in the [README](../../README.md) and in [Running VectolynCloud](../cloud/operations.md). This documentation is English only, like the reference pages in `docs/cloud/`.

| Page | Content |
|---|---|
| This page | Setup, build and test, architecture overview, where things live |
| [Module reference](modules.md) | Every Gradle module: purpose, entry types, dependencies, reference page |
| [Releasing](releasing.md) | Version rules, release checklist, what the workflow produces, how to check it |
| [Contributing](../../CONTRIBUTING.md) | Branches, commits, sign-off, code rules, pull requests |
| [Architecture](../cloud/architecture.md) | Module graph, libraries, build setup, fuzz tests, cloud home |

## Set up

You need git and a JDK. Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain; if no JDK 25 is installed, Gradle downloads one. The bytecode target is Java 21. Details and the reasons: [Java compatibility](../java-compatibility.md).

```sh
git clone https://github.com/VectoDE/VectolynCloud.git
cd VectolynCloud
./gradlew build          # gradlew.bat build on Windows
```

Every dependency is checked against a SHA-256 checksum in `gradle/verification-metadata.xml`. After you add, remove or update a dependency run `bash gradle/update-verification-metadata.sh` and commit the result with your change ([dependency verification](../dependency-verification.md)).

## Run, build and test

| Goal | Command |
|---|---|
| Build everything, run all tests | `./gradlew build` |
| Tests of one module | `./gradlew :cloud:config:test` |
| Tests on a Java 21 runtime (CI does this as well) | `./gradlew test -PtestJava=21` |
| Build `VectolynCloud.jar` | `./gradlew :cloud:launcher:cloudJar` (result in `cloud/launcher/build/cloud/`) |
| Start the cloud from the checkout, home in `run/` | `./gradlew :cloud:node:runLocal --console=plain` |
| End-to-end test with the real jar and fake servers | `./gradlew :cloud:node:e2eTest` |
| End-to-end test with real downloads and servers | `./gradlew :cloud:node:e2eLiveTest` (runs weekly in CI, see [node](../cloud/node.md#end-to-end-test)) |
| Fuzz one test method locally | see [fuzz tests](../cloud/architecture.md#fuzz-tests) |

Tests that load Paper or Velocity classes carry `@Tag("requires-java25")` and are skipped on the Java 21 run. `run/` is ignored by Git; delete it to start over. More on the local start: [launcher](../cloud/launcher.md#local-start-from-the-repository).

CI ([`ci.yml`](../../.github/workflows/ci.yml)) builds and tests with JDK 25 on Linux and Windows, runs the tests again on Java 21 and runs the `Integration` job (`e2eTest`) when cloud code changed. CodeQL, Dependency Review, Scorecard, ClusterFuzzLite and the DCO and PR title checks run beside it. The whole list is in `.github/workflows/`.

## Architecture overview

```
 Java and Bedrock players
          │
   Proxy (Velocity) ──── bridge ────┐
          │                         │  cloud channel: Netty server in the node,
   Backends (Paper, Fabric,         │  plain JDK client in the bridges
   PandaSpigot) ──── bridge ────────┤
                                    ▼
                          ┌───────────────────┐       REST / WebSocket
                          │  VectolynCloud    │◄────  (cloud/api)
                          │  node             │
                          └───────────────────┘
                 config files, service processes, scaling,
                 monitoring, backups, cluster
```

- **One launcher, one node.** `cloud/launcher` holds the class the JVM loads first. It is compiled for Java 8 so that old Java versions get a readable message. It checks Java and rights, unpacks the embedded libraries and loads the node by name ([launcher](../cloud/launcher.md#start-order)). `cloud/node` is the only module that knows all other cloud modules ([node](../cloud/node.md#start-order)).
- **Configuration is one layer.** Setup assistant, console commands, files and the API change configuration only through `ConfigService` in `cloud/config`, so every path applies the same validation ([config](../cloud/config.md)).
- **Services are plain processes.** The node starts each server JVM directly, without a wrapper jar ([architecture](../cloud/architecture.md#no-wrapper-jar)). The templates in `templates/<Server>/` are shipped in the jar, rendered with placeholders and secrets into the cloud home, and only for servers that are wanted ([template](../cloud/template.md)).
- **One channel to the servers.** Proxy and backends run a bridge that talks to the node over the cloud channel. The wire format lives in `common/protocol` and has no dependencies; the client in `common/client` uses plain JDK sockets so that no second Netty ends up in a server ([protocol](../cloud/protocol.md), [network](../cloud/network.md), [bridges](../cloud/bridges.md)).
- **Software is downloaded, checked and pinned.** `cloud/download` allows only listed hosts, verifies checksums, guards archive extraction and records every artifact with its hash in `config/software.lock.yml` ([download](../cloud/download.md)).
- **Code that parses outside input has a fuzz test.** See [Contributing](../../CONTRIBUTING.md#code).

The stages that are not built yet (Vectolyn plugin, lobby and game modes, dashboard, bot, website, store) are on the [roadmap](https://github.com/VectoDE/VectolynCloud/wiki/Roadmap). Nothing in this documentation describes them as working.

## Where things live

```
cloud/<module>/                 one Gradle module per part of the cloud
common/protocol, common/client  wire format and client, shared with the bridges
plugins/<feature>/<platform>/   one module and one jar per feature and platform
templates/<Server>/             server configs with placeholders
cloud/dist/                     start scripts, Dockerfile, compose file, Pterodactyl egg, systemd unit
docs/cloud/                     reference pages per module
docs/wiki/                      source of the GitHub wiki (English and German)
build-logic/                    Gradle convention plugins
tools/release-banner/           release banner and tag rules
.github/workflows/              CI, release, Scorecard, wiki sync, Pages
```

## Typical changes

- **A console command.** Register it with `Command.builder(...)` as shown in [console](../cloud/console.md#adding-commands). Texts need English and German. A command that takes a secret as argument is `sensitive()`.
- **A configuration key.** Add it to the model in `cloud/config` and to the validation. Change `schema-version` and add a migration if existing files change meaning. Then update [config.md](../cloud/config.md).
- **An API endpoint.** Route in `cloud/api`, call into the node through `ApiBackend`, scope check, and the entry in [`api.openapi.yaml`](../cloud/api.openapi.yaml) and [api.md](../cloud/api.md).
- **A message on the cloud channel.** New type in `common/protocol`, registered in `MessageRegistry`; follow the version rules in [protocol](../cloud/protocol.md#versions).
- **A downloadable software component.** Catalog entry and provider in `cloud/download`; the host has to be on the allowlist ([download](../cloud/download.md)).
- **A wiki page.** Edit `docs/wiki/` in a pull request, English page without suffix and German page with `-DE`. The wiki is overwritten from there after every merge.

## Help

Questions go to [Discussions](https://github.com/VectoDE/VectolynCloud/discussions). Security problems follow [SECURITY.md](../../SECURITY.md).
