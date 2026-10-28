# VectoraCloud architecture

VectoraCloud is the Java application behind `java -jar VectoraCloud.jar`. It renders the server templates in `Server-Network/`, downloads and verifies the server software, sets up the database, starts and watches every proxy and server, and offers a console and later an API.

This page describes the module layout. Each module gets its content in its own pull request (see the [roadmap](../wiki/Roadmap.md)). Done so far: [protocol](protocol.md), [configuration](config.md), [templates](template.md), [downloads](download.md) and the [launcher](launcher.md). The other modules are still empty shells with their final dependencies.

## Modules

| Module | Package | Purpose |
|---|---|---|
| `Vectora-Protocol` | `net.vectora.protocol` | Wire format of the cloud channel. No dependencies at all, because it runs inside every proxy and server. See [protocol.md](protocol.md). |
| `Vectora-CloudClient` | `net.vectora.cloud.client` | Client side of the channel, used by the bridges and later by the Vectora plugin. Uses only the JDK for networking. |
| `VectoraCloud/cloud-config` | `net.vectora.cloud.config` | Configuration model: `cloud.yml`, `groups/`, `tasks/`, `nodes.yml`, each with a `schema-version`, plus `secrets/vectora.env`. The API speaks JSON with the same model. See [config.md](config.md). |
| `VectoraCloud/cloud-template` | `net.vectora.cloud.template` | Turns `Server-Network/<Server>/template/` into `runtime/`: placeholders, secrets, wanted and unwanted servers. See [template.md](template.md). |
| `VectoraCloud/cloud-download` | `net.vectora.cloud.download` | Downloads server software, plugins, mods and JREs over HTTPS from an allowlist of hosts, verifies checksums, guards against zip slip. Owns `software.lock.yml`, the record of every resolved artifact with its hash. See [download.md](download.md). |
| `VectoraCloud/cloud-network` | `net.vectora.cloud.network` | Netty server for the cloud channel (bridges, nodes) and later the REST and WebSocket API. |
| `VectoraCloud/cloud-database` | `net.vectora.cloud.database` | Database setup (managed MariaDB, Docker or external server) and central sizing of the connection pools. |
| `VectoraCloud/cloud-node` | `net.vectora.cloud.node` | The node itself: services, groups and tasks, JRE management, console, setup flow, scaling, monitoring, backups, updates, cluster. |
| `VectoraCloud/cloud-launcher` | `net.vectora.cloud.launcher` | Entry point of `VectoraCloud.jar`: arguments, refusal to run as root or administrator (unless `--allow-root` or `VECTORA_ALLOW_ROOT=true`), node startup. See [launcher.md](launcher.md). |
| `VectoraCloud/cloud-bridge-velocity` | `net.vectora.cloud.bridge.velocity` | Runs inside Velocity: reports state to the cloud, receives commands. Compiles against `velocity-api` (compile only). |
| `VectoraCloud/cloud-bridge-paper` | `net.vectora.cloud.bridge.paper` | Runs inside Paper and does the same. Compiles against `paper-api` (compile only). On the legacy backends it also does the BungeeGuard check. |

From stage 3 on, the Vectora plugin takes over status and messaging from the bridges. It uses `Vectora-CloudClient` as well.

The 1.8.8 groups behind the legacy proxy are described in [Legacy 1.8.8 groups](legacy-1.8.8.md).

## Dependency graph

Arrows point from a module to what it depends on. Only project dependencies are shown; the libraries follow in the next section.

```mermaid
graph TD
    launcher[cloud-launcher] --> node[cloud-node]
    node --> config[cloud-config]
    node --> template[cloud-template]
    node --> download[cloud-download]
    node --> network[cloud-network]
    node --> database[cloud-database]
    database --> config
    database --> download
    network --> config
    network --> protocol[Vectora-Protocol]
    client[Vectora-CloudClient] --> protocol
    bvel[cloud-bridge-velocity] --> client
    bpap[cloud-bridge-paper] --> client
```

The rules behind it:

- `Vectora-Protocol` depends on nothing. The cloud and the clients share it, so neither side can drift from the wire format.
- Code that runs inside a proxy or server (`Vectora-CloudClient`, the bridges) never pulls in Netty, Jackson or JLine. The servers ship their own copies of some of these, and a second copy on their class path invites conflicts.
- `cloud-config` sits at the bottom of the cloud modules. Console, files and later the dashboard all go through the same configuration layer.
- `cloud-database` uses `cloud-download` because the managed mode downloads the MariaDB server itself.
- Only `cloud-node` knows all other cloud modules. `cloud-launcher` only knows `cloud-node`.

## Runtime libraries

| Library | Version | Used by | Why |
|---|---|---|---|
| Netty (`netty-transport`, `netty-handler`, `netty-codec-http`) | 4.2.19.Final | `cloud-network` | Non-blocking server for many long-lived connections (every proxy and server keeps one open), TLS 1.3 for the mTLS cluster links, and HTTP plus WebSocket for the API from the same stack. Velocity and Paper are built on Netty too, so its behaviour under load is well known. |
| JLine 3 (`jline-reader`, `jline-terminal`, `jline-terminal-jni`) | 3.30.17 | `cloud-node` | Console with line editing, history and tab completion on Windows, Linux and macOS, as in CloudNet. The terminal provider `jni` works on Java 21. `ffm` (`jline-terminal-ffm`) needs Java 22+ and is only an option on newer runtimes, see [Java compatibility](../java-compatibility.md). |
| Jackson 3 (`jackson-databind`, `jackson-dataformat-yaml`) | 3.2.4 | `cloud-config`, `cloud-download` | One data model for the YAML files and the JSON API, with explicit `schema-version` handling and exact error positions for broken config files. `cloud-download` also reads the download APIs, `software.lock.yml` and its catalog with it. |
| MariaDB Connector/J (`mariadb-java-client`) | 4.0.0 | `cloud-database` | Talks to the managed or external MariaDB/MySQL server: creating databases and users, checking the connection, sizing the pools. |

Test only: JUnit 6 and Jazzer 0.30.0 (fuzz tests, see below). Compile only: `velocity-api` 4.3.1 and `paper-api` 26.3 in the bridges. The proxy and the server provide them at runtime.

Versions live in `gradle/libs.versions.toml`. Dependabot proposes updates.

## No wrapper jar

CloudNet and similar clouds start each server through an extra wrapper jar that sits between the JVM and the server. VectoraCloud does not. The node starts the server JVM directly with `ProcessBuilder` (the matching JRE, the server jar, the generated arguments) and reads its output.

The reasons:

- One process less per server, and no class-loader tricks around the server's own startup.
- The server runs exactly as its own documentation describes, so a problem can be reproduced without the cloud.
- What a wrapper would do happens elsewhere. Process state, exit codes and logs come from the process handle. Status and messaging come from the bridges (stage 2) and the Vectora plugin (stage 3) over the cloud channel.

## Java levels

All cloud modules, `Vectora-Protocol` and `Vectora-CloudClient` are built with `--release 21` and run on Java 21 and newer. The bridges also have bytecode 21, but `velocity-api` 4.3 and `paper-api` 26.3 are compiled for Java 25 (class file version 69). The bridges therefore run only where the proxy or server runs, on Java 25. Details in [Java compatibility](../java-compatibility.md).

## Build setup

The shared Gradle setup lives in the included build `build-logic/` as convention plugins:

| Plugin | Used by | What it does |
|---|---|---|
| `vectora.java-conventions` | every Java module | Java 25 toolchain, `--release 21`, `-Xlint:all -Werror` (a module may switch `-Werror` off and has to say why), JUnit, `-PtestJava=21`, reproducible jars. |
| `vectora.java25-api-conventions` | bridges, `Vectora-Lobby`, `Vectora-PortalBridge` | Everything above, plus `disableAutoTargetJvm()` for the Java 25 server APIs. |
| `vectora.fuzz-conventions` | modules with fuzz tests (`Vectora-Protocol`, `cloud-config`, `cloud-template`, `cloud-download`) | Adds Jazzer, runs `@FuzzTest` methods in regression mode in every test run; `-Pfuzz` switches to fuzzing mode. |

## Fuzz tests

Code that parses untrusted input (protocol frames, config files, downloaded archives, API requests) gets Jazzer fuzz tests next to its unit tests.

- **Regression mode, in every test run:** each `@FuzzTest` runs once per stored input in `src/test/resources/<package>/<TestClass>Inputs/<method>/`, plus an empty input. This is a normal JUnit test. It runs on Linux and Windows, on Java 21 and 25 (checked with Jazzer 0.30.0 and `VarIntsFuzzTest`).
- **Fuzzing mode, nightly:** `.github/workflows/fuzz-nightly.yml` finds every `@FuzzTest` method and fuzzes each one for a fixed time on Linux with Java 21. A crash fails the job and is uploaded as an artifact. The input then goes into the inputs directory above, together with the fix.
- **Locally:** `./gradlew :Vectora-Protocol:test --tests 'net.vectora.protocol.VarIntsFuzzTest.readFailsOnlyWithProtocolException' -Pfuzz -PfuzzMaxDuration=1m`. Always select one method, because Jazzer fuzzes only the first fuzz test it meets per run. The generated corpus goes to `.cifuzz-corpus/`, which git ignores.

## Cloud home

The cloud keeps its data in files, not in a database. In the repository checkout the home is `Server-Network/`: `cloud.yml`, `groups/`, `tasks/`, `nodes.yml`, `software.lock.yml`, `secrets/`, `temp/`, `cache/`, `backups/`, `logs/`, `state/services.json`, next to the server templates. Only templates and example files are committed.
