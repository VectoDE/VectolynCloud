# Java compatibility

## Policy

- Bytecode target for every Vectora module is **Java 21** (`--release 21`). The compiler always runs on a Java 25 toolchain.
- A module may only raise its release target when a dependency makes 21 impossible. Such a module is marked with a comment in its `build.gradle.kts` and listed in the table below.
- Code that needs Java 25 at runtime is annotated with `@RequiresJava25`, checks the running version, and always has a Java 21 path. The annotation arrives with the core module in stage 3; no current code needs Java 25 APIs.

The root `build.gradle.kts` sets the defaults (toolchain 25, release 21). JDKs are taken from the local machine; if one is missing, the Foojay resolver in `settings.gradle.kts` downloads it.

## What CI tests

| Job | Compiles with | Tests run on | What it proves |
|---|---|---|---|
| `Build (JDK 25, ubuntu-latest)`, `Build (JDK 25, windows-latest)` | JDK 25, `--release 21` | Java 25 | Full build and every test, on Linux and Windows. Java 25 is the runtime the plugins ship for, because Paper 26.3 needs it. |
| `Tests on Java 21` | JDK 25, `--release 21` | Java 21 (`-PtestJava=21`) | The compiled code and its resources load and pass their tests on a Java 21 runtime. |

On Java 21 the tests tagged `requires-java25` are skipped. They load paper-api classes (class file version 69), which a Java 21 JVM cannot load. Today these are `PluginDescriptorTest.mainClassIsAJavaPlugin` in both modules and `PortalBridgeTest`, whose class under test extends `JavaPlugin`. `BytecodeLevelTest` runs on both: it reads the main class file without loading it and checks major version 65.

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

No module currently needs release 25.

## Why paper-api 26.3 does not force release 25

Checked on 2026-10-28 with `paper-api 26.3.build.226-stable`:

1. The classes in the paper-api jar are class file version 69 (Java 25).
2. Its Gradle module metadata declares `org.gradle.jvm.version = 25`. With release 21, Gradle refuses to resolve it: *"Dependency resolution is looking for a library compatible with JVM runtime version 21, but 'io.papermc.paper:paper-api:26.3.build.226-stable' is only compatible with JVM runtime version 25 or newer."*
3. javac from JDK 25 reads version-69 class files on the classpath even with `--release 21`. A test compile against paper-api with `--release 21` succeeded, and so does the full Gradle build of both modules once the variant check is switched off.

So the modules call `java { disableAutoTargetJvm() }`. This only turns off Gradle's variant check; the bytecode stays at 21 (verified: class file major version 65 in the built jars).

What this does not change: Paper 26.3 itself needs Java 25, so these jars still run only on a Java 25 server. Bytecode 21 just means the code does not use Java 22+ language or library features, so it can be reused for older Paper versions later.

Risk to keep in mind: if a future paper-api exposes Java 22+ types in signatures the modules use, compiling with `--release 21` will fail. In that case the affected module moves to release 25, gets marked here and in its build file, and the adapter approach below becomes mandatory for it.

## Plan for stage 3: Java 21 core plus version adapters

Stage 3 builds the Vectora plugin base so that one plugin supports MC 1.8.8 up to the latest version:

- **Core** (platform-independent logic, messaging, permissions model): release 21, no dependency on a specific Paper version.
- **Adapters**, one per MC version range: each compiles against the matching server API and uses the lowest release target that API allows. Adapters for modern Paper (26.x) compile against a Java 25 API, adapters for older versions run on Java 21 or lower.
- The platform jars (`Vectora-Paper.jar`, `Vectora-Velocity.jar`, `Vectora-Fabric.jar`) bundle the core and pick the adapter at runtime.

Until then the two existing modules stay as they are, with release 21 and the variant check disabled.
