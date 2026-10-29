# Java compatibility

## Policy

- Bytecode target for every Vectora module is **Java 21** (`--release 21`). The compiler always runs on a Java 25 toolchain. The one exception is the class `Bootstrap` in `cloud-launcher`, which is built with `--release 8` (see below).
- A module may only raise its release target when a dependency makes 21 impossible. Such a module is marked with a comment in its `build.gradle.kts` and listed in the table below.
- Code that needs Java 25 at runtime is annotated with `@RequiresJava25`, checks the running version, and always has a Java 21 path. The annotation arrives with the core module in stage 3; no current code needs Java 25 APIs.

The convention plugins in `build-logic/` set the defaults (toolchain 25, release 21); every module applies `vectora.java-conventions` or `vectora.java25-api-conventions`. JDKs are taken from the local machine; if one is missing, the Foojay resolver in `settings.gradle.kts` downloads it.

## What CI tests

| Job | Compiles with | Tests run on | What it proves |
|---|---|---|---|
| `Build (JDK 25, ubuntu-latest)`, `Build (JDK 25, windows-latest)` | JDK 25, `--release 21` | Java 25 | Full build and every test, on Linux and Windows. Java 25 is the runtime the plugins ship for, because Paper 26.3 needs it. |
| `Tests on Java 21` | JDK 25, `--release 21` | Java 21 (`-PtestJava=21`) | The compiled code and its resources load and pass their tests on a Java 21 runtime. |

On Java 21 the tests tagged `requires-java25` are skipped. They load paper-api or velocity-api classes (class file version 69), which a Java 21 JVM cannot load. Today these are `PluginDescriptorTest.mainClassIsAJavaPlugin` in both plugin modules, `PortalBridgeTest` (its class under test extends `JavaPlugin`), `PaperBridgeApiTest` and `VelocityBridgeApiTest`. Every module has a `BytecodeLevelTest` that runs on both: it reads a class file without loading it and checks major version 65. The Jazzer fuzz tests in `Vectora-Protocol`, `Vectora-CloudClient`, `cloud-config`, `cloud-template`, `cloud-download`, `cloud-network`, `cloud-database`, `cloud-node`, `cloud-console` and `cloud-setup` run in regression mode on both runtimes, and so do the loopback tests of the cloud channel.

Every run uploads the JUnit XML of its tests as an artifact (`junit-xml-ubuntu-latest`, `junit-xml-windows-latest`, `junit-xml-java21`). That shows which tests ran on which runner, including the ones that need POSIX permissions or symlinks and are skipped on Windows.

Locally:

```sh
./gradlew build                  # JDK 25 toolchain, all tests
./gradlew test -PtestJava=21     # same compile, tests on a Java 21 runtime
```

`-PtestJava=21` uses an installed JDK 21 or lets Foojay download one.

## Status per module

| Module | Release target | Compiles against | Runtime needs | Notes |
|---|---|---|---|---|
| `Vectora-Lobby` | 21 | paper-api 26.3 (class files for Java 25) | Java 25 (Paper 26.3) | uses `disableAutoTargetJvm()`, see below |
| `Vectora-PortalBridge` | 21 | paper-api 26.3 (class files for Java 25) | Java 25 (Paper 26.3) | uses `disableAutoTargetJvm()`, see below |
| `Vectora-Protocol` | 21 | JDK only | Java 21 | no dependencies |
| `Vectora-CloudClient` | 21 | Vectora-Protocol | Java 21 | runs inside proxies and servers; JDK sockets only, no Netty (see [network.md](cloud/network.md)) |
| `VectoraCloud/cloud-config`, `cloud-template`, `cloud-download`, `cloud-network`, `cloud-database`, `cloud-launcher` | 21 (`Bootstrap` in `cloud-launcher`: 8) | Jackson 3, Netty 4.2, MariaDB Connector/J 4 (all Java 17 or lower) | Java 21 | |
| `VectoraCloud/cloud-console` | 21 | cloud-config, JLine 3 | Java 21 | JLine `ffm` provider only on Java 22+, see below |
| `VectoraCloud/cloud-node` | 21 | as above, plus cloud-console (brings JLine 3) | Java 21 | |
| `VectoraCloud/cloud-setup` | 21 | cloud-console, cloud-database | Java 21 | |
| `VectoraCloud/cloud-bridge-velocity` | 21 | velocity-api 4.3.1 (class files for Java 25) | Java 25 (Velocity 4.3) | uses `disableAutoTargetJvm()` |
| `VectoraCloud/cloud-bridge-paper` | 21 | paper-api 26.3 (class files for Java 25) | Java 25 (Paper 26.3) | uses `disableAutoTargetJvm()` |

No module currently needs release 25.

## Exception: `Bootstrap` in `cloud-launcher` is built for Java 8

`net.vectora.cloud.launcher.Bootstrap` is the `Main-Class` of `VectoraCloud.jar`. It lives in its own source set `bootstrap` and is compiled with `--release 8`; everything else in the module stays at 21.

The reason: an admin who starts the cloud on Java 8 to 20 has to see a readable "Java too old" message in German or English, with the version found and where to get Java 21, and exit code 3. If the first class the JVM loads had class file version 65, an old runtime would stop with `UnsupportedClassVersionError` before any Vectora code runs. So `Bootstrap` uses only Java 8 API, checks `java.specification.version` and calls `Launcher.main` by reflection once the version is fine. `BootstrapTest` checks major version 52. The build file turns off javac's "release 8 is obsolete" lint for this source set only, because `-Werror` would fail on it. Details in [the launcher page](cloud/launcher.md).

Right after the Java check the launcher refuses to run as root or administrator. If it cannot tell whether the process is elevated (state UNKNOWN), it starts anyway and prints a clearly framed warning that tells an administrator or root to stop and restart as a normal user (maintainer decision of 2026-10-28). A start that is known to be elevated still needs `--allow-root` or `VECTORA_ALLOW_ROOT=true`. See [Root and administrator check](cloud/launcher.md#root-and-administrator-check).

VectoraCloud itself (`java -jar VectoraCloud.jar`) runs on Java 21. The servers it starts get their own JRE per group, so the cloud's runtime does not limit them. See [JREs for the servers](#jres-for-the-servers).

## JREs for the servers

Each server group runs on its own Java runtime, chosen by `JavaRequirement` in `cloud-download` ([JRE management](cloud/download.md#jre-management)):

| Group | Software | Minimum from the source | Java |
|---|---|---|---|
| Proxy, legacy proxy | Velocity | Fill `java.version.minimum` | 25 |
| Lobby, Survival, Creative, End, Minigames | Paper 26.3 | Fill `java.version.minimum` (25) | 25 |
| Mod-Server | Fabric for 26.3 | Mojang `javaVersion` (25) | 25 |
| 1.8.8 groups behind the legacy proxy | PandaSpigot 1.8.8 | Mojang `javaVersion` (8) | 21 |

The rule: `java: <major>` in the group file wins if it is not below the minimum. Otherwise legacy groups (PandaSpigot) get 21 and every other group 25. A minimum above that default wins. PandaSpigot gets 21 rather than 25 because Netty prints a `sun.misc.Unsafe` warning under Java 25 (spike PR 22, checked weekly by the legacy fork smoke test).

An installed system Java is used if its feature version is exactly the chosen one; otherwise the cloud downloads Eclipse Temurin (JRE) from Adoptium into `<home>/jre/`. Temurin JREs 21 and 25 exist for Windows x64, Linux x64 and aarch64 and macOS x64 and aarch64 (checked on 2026-10-28). The weekly download smoke test resolves all of them and starts the JRE 21 it installs.

## Marked Java 22+ spot: JLine terminal provider `ffm`

JLine 3 talks to the terminal through a provider. `jline-terminal-ffm` uses the Foreign Function & Memory API, which is final only since Java 22; its classes cannot load on Java 21. `cloud-console` ships both `jline-terminal-ffm` and `jline-terminal-jni` and picks one at start (`TerminalSelector`):

- **Java 22+:** `ffm` first. The choice is a check of the running version (`Runtime.version().feature() >= 22`), never class-path order, so the ffm classes are not even touched on Java 21.
- **Java 21 path:** `jni` (native helper bundled for Windows, Linux and macOS). If no native provider loads, JLine's `dumb` terminal. Without a terminal at all (Docker without TTY, Pterodactyl, systemd) the console reads stdin line by line.
- **Tests:** `TerminalSelectorTest.ffmProviderLoadsOnJava25` is tagged `requires-java25` and is skipped on Java 21. On Java 21, `ffmProviderCannotLoadOnJava21` proves the ffm classes do not load there and `detectInATestRunNeverPicksFfmBelowJava22` that the selector never picks them; `jniProviderLoadsOnEveryRuntime` runs on both. The selection logic itself is tested for every combination of version, request and available providers.
- Both native providers warn on Java 24+ unless native access is allowed. `VectoraCloud.jar` has to carry `Enable-Native-Access: ALL-UNNAMED` in its manifest; the console tests pass `--enable-native-access=ALL-UNNAMED`.

See [console.md](cloud/console.md#terminal).

## Why paper-api 26.3 does not force release 25

Checked on 2026-10-28 with `paper-api 26.3.build.226-stable` (the same holds for `velocity-api 4.3.1`, also class file version 69):

1. The classes in the paper-api jar are class file version 69 (Java 25).
2. Its Gradle module metadata declares `org.gradle.jvm.version = 25`. With release 21, Gradle refuses to resolve it: *"Dependency resolution is looking for a library compatible with JVM runtime version 21, but 'io.papermc.paper:paper-api:26.3.build.226-stable' is only compatible with JVM runtime version 25 or newer."*
3. javac from JDK 25 reads version-69 class files on the classpath even with `--release 21`. A test compile against paper-api with `--release 21` succeeded, and so does the full Gradle build of both modules once the variant check is switched off.

So these modules apply `vectora.java25-api-conventions`, which calls `java { disableAutoTargetJvm() }`. This only turns off Gradle's variant check; the bytecode stays at 21 (verified: class file major version 65 in the built jars).

What this does not change: Paper 26.3 itself needs Java 25, so these jars still run only on a Java 25 server. Bytecode 21 just means the code does not use Java 22+ language or library features, so it can be reused for older Paper versions later.

Risk to keep in mind: if a future paper-api exposes Java 22+ types in signatures the modules use, compiling with `--release 21` will fail. In that case the affected module moves to release 25, gets marked here and in its build file, and the adapter approach below becomes mandatory for it.

## Plan for stage 3: Java 21 core plus version adapters

Stage 3 builds the Vectora plugin base so that one plugin supports MC 1.8.8 up to 26.3:

- **Core** (platform-independent logic, messaging, permissions model): release 21, no dependency on a specific Paper version.
- **Adapters**, one per MC version range: each compiles against the matching server API and uses the lowest release target that API allows. Adapters for modern Paper (26.x) compile against a Java 25 API, adapters for older versions run on Java 21 or lower.
- The platform jars (`Vectora-Paper.jar`, `Vectora-Velocity.jar`, `Vectora-Fabric.jar`) bundle the core and pick the adapter at runtime.

Until then the two existing modules stay as they are, with release 21 and the variant check disabled.
