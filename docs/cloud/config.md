# VectoraCloud configuration

Module `cloud/config`, package `net.vectora.cloud.config`. This is the one configuration layer of the cloud: the console, the setup assistant, hand-edited files and later the REST API and the dashboard all read and change the configuration through it.

## Files in the cloud home

The cloud home is the folder the cloud runs in (`VECTORA_HOME`, by default the folder of the start script). In production it lives outside any checkout. For local development and testing, `./gradlew :cloud:node:runLocal` builds `VectoraCloud.jar` and starts it with `run/` in the repository root as the home (see [launcher](launcher.md#local-start-from-the-repository)). `.gitignore` ignores `run/` as a whole, so its configuration, secrets, worlds, logs and downloads never reach a commit. If the cloud is started directly in the repository root by mistake, `.gitignore` also keeps its files (`cloud.yml`, `nodes.yml`, `groups/`, `tasks/`, `backups/`, `state/`, `cache/`, `temp/`, the server folders) out of commits, but backups and secrets would still sit loose in the working tree.

```
<home>/cloud.yml           network and this node
<home>/nodes.yml           all nodes of the cluster
<home>/groups/<name>.yml   what a service runs (template, software, version, heap, plugins)
<home>/tasks/<name>.yml    how many services of a group run, and where
<home>/secrets/vectora.env secrets, KEY=VALUE, owner-only
```

`software.lock.yml`, `temp/`, `cache/`, `backups/`, `logs/` and `state/` belong to other modules.

On the first start the cloud writes a default `cloud.yml` and `nodes.yml` and creates `groups/` and `tasks/`. Every file starts with `schema-version`. Field names are kebab-case, enum values lower case.

The cloud rewrites a file when it changes it. Comments in a file are not kept then; the cloud writes a short header instead. Keep notes elsewhere, for example in `docs/`.

### `cloud.yml`

```yaml
schema-version: 1
network-name: Vectora        # 1-32 letters, digits, space, . _ -
language: en                 # en or de
eula-accepted: false         # eula.txt is written only after this is true
setup-complete: false        # needs eula-accepted: true
node-id: node-1              # must exist in nodes.yml
# fallback-group: lobby      # optional: where the proxies send a kicked player when the player's own group has no free server; default lobby, must be an existing group when set
servers:                     # the "wanted" switch per server; a missing entry counts as false
  proxy: true
  lobby: true
  survival: true
  creative: true
  mod: true
  end: true
  database: true
  minigame: true
  legacy-server: true        # the 1.8.8 servers (every PandaSpigot group); true by default, but they only run while legacy-proxy.enabled is true
proxy:
  bind: 0.0.0.0
  port: 25565
  bedrock-port: 19132        # Geyser, UDP plus TCP
legacy-proxy:                # second Velocity (bungeeguard) for the 1.8.8 groups
  enabled: false
  port: 25564
database:
  mode: managed              # managed, docker, external or h2 (embedded fallback, see database.md)
  host: 127.0.0.1
  port: 3307
  name: luckperms
  user: luckperms
  # docker-image: mariadb:11.8   only and required with mode: docker
api:
  enabled: false
  bind: 127.0.0.1            # anything except loopback needs TLS
  port: 8085
  tls:
    enabled: false
    # keystore: secrets/api.p12  required when enabled, relative to the home
  # cors-origins: [https://dashboard.example.org]   optional, up to 16 origins, off when empty
```

Passwords are not in `cloud.yml`. They are in `secrets/vectora.env`.

### `groups/<name>.yml`

```yaml
schema-version: 1
template: Lobby-Server       # folder in the home that holds template/
software: paper              # paper, velocity, fabric or pandaspigot
minecraft-version: "26.3"    # quote it: an unquoted 1.20 would be a number
java: auto                   # auto or a major version (8 to 99)
heap:
  min: 256M                  # M or G
  max: 768M
combat: modern               # legacy (1.8 style) or modern
wanted: false                # optional; only written when off. Switches one group off, for example one of several minigames
drain-timeout: 30s           # optional, 5s to 10m, default 30s; only written when set
catalog:                     # plugins and mods from the download catalog
  - id: luckperms
    kind: transitional       # plugin, mod or transitional
  - id: vectora-paper
    kind: plugin
process:                     # optional, see docs/cloud/node.md; every key has a default
  jvm-flags: [-XX:+UseG1GC]  # up to 64 flags, each -[A-Za-z0-9_.:+=/,@%-]{1,255}
  stop-command: stop         # omit for the default: shutdown for Velocity, stop otherwise
  stop-timeout: 60s          # 1s to 600s
  restart:                   # if given, all four keys are required
    on-crash: true
    max-attempts: 5          # 1 to 1000 crashes in a row
    backoff: 5s              # doubled per crash, at most max-backoff
    max-backoff: 300s
```

`process.jvm-flags` must not set what the node controls: `-Xms`, `-Xmx`, `-jar`, `-cp`/`-classpath`/`--class-path`, `-XX:Flags=`, `-XX:VMOptionsFile=`, and the version and help switches are rejected. On top of that, a change through the [REST API](api.md) may only add flags from the [allowlist](#jvm-flag-allowlist).

`drain-timeout` is how long a service of the group may take to send its players to other services before the scaler stops it. If players are still on it after that time, the stop is called off and it keeps running; see [scaling.md](scaling.md#rules).

`transitional` marks the third-party plugins the cloud installs until Vectora's own replacement is ready (LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard, Chunky, spark, ProxyPortal). `mod` entries are only allowed with `fabric`, `plugin` entries only without it. `pandaspigot` only runs `1.8.8`.

### `tasks/<name>.yml`

```yaml
schema-version: 2
group: lobby                 # must exist in groups/
scaling:
  static-services: 1         # 0 to 1000, at most min-services; required
  min-services: 1            # 0 to 1000
  max-services: 4            # 1 to 1000, at least min-services
  players-per-service: 50
  scale-up-at: 75            # load of the group in percent, 1 to 100
  scale-down-below: 50       # percent, 0 to 99, lower than scale-up-at; default 50
  scale-down-after: 5m       # s, m or h; at most 7 days
  cooldown: 60s
nodes: [node-1]              # empty: any node
```

A task mixes static and dynamic services. The first `static-services` services are static: they keep their folder (`<template>/runtime`, `runtime-2`, …) and are never stopped by the scaler. Every service above that is dynamic: it runs in `temp/services/<id>` and its folder is deleted when it stops. `static-services = max-services` is a purely static task, `static-services = 0` a purely dynamic one.

What the node does with these values (when a service starts or stops, the memory budget, what is never stopped) is in [scaling.md](scaling.md).

#### From schema-version 1

Version 1 had a task-wide `mode` instead of `static-services`. The migration replaces it:

| Version 1 | Version 2 |
|---|---|
| `mode: static` | `static-services` = `min-services`, `max-services` = `min-services` (a static task never scaled) |
| `mode: dynamic` | `static-services: 0` |
| `mode` missing | error `required` at `mode` |
| any other value | error `invalid-value` at `mode` (`static or dynamic`) |

`scale-down-below` did not exist in version 1. The migration sets it to `min(50, scale-up-at / 2)`, rounded down (`scale-up-at` 80 gives 40, 75 gives 37, 50 gives 25, 1 gives 0), so it stays below `scale-up-at`. A version-1 file that already has the field keeps its value. A version-1 task with `mode: static` and `min-services: 0` ran no service. It becomes one static service (`static-services`, `min-services` and `max-services` 1), and the node logs a warning with the file name and the change.

### `nodes.yml`

```yaml
schema-version: 1
nodes:
  - id: node-1
    host: 127.0.0.1
    port: 7010               # cloud channel
    service-ports:           # ports this node hands out to services
      first: 30000
      last: 30999
    max-memory: 8G           # optional, at least 512M; missing: worked out by the node
    memory-reserve:          # optional, every field 1M to 64G; missing: the default
      system: 1G             # operating system and other programs
      node-overhead: 256M    # the node process itself
      database: 512M         # only counted when this cloud runs the database (managed or docker)
```

`max-memory` caps the memory the node gives its services: the scaler's memory budget is `max-memory`, at most the measured memory of the machine. Without it the node takes the measured memory minus `memory-reserve`, memory the node keeps for itself and never hands to services; see [scaling.md](scaling.md#memory-budget). Only the fields that are set are written; a missing field or section means the default shown above. `NodeConfig.memoryReserve()` returns the reserve with the defaults filled in. The `database` part counts only when `servers.database` is on and the database runs in `managed` or `docker` mode. Both can be changed with `node set` in the console, `PUT /nodes/{name}` in the API, the `node:` section of the setup file and the advanced settings of the setup assistant.

### Names

Group, task and node names match `[a-z0-9][a-z0-9_-]{0,31}` and must not be a Windows device name (`con`, `prn`, `aux`, `nul`, `com0` to `com9`, `lpt0` to `lpt9`); `console` or `con1` are fine. Group and task names are the file names, so they cannot disagree with the file. Only lower case is allowed because Windows and macOS do not tell `Lobby.yml` from `lobby.yml`. The rule is the same as for `GroupName` and `NodeId` in `common/protocol` (1 to 32 characters, lower case, no device names), so every configured name can be sent. A service id there (`ServiceId`) may have up to 64 characters, so that `<group>-<index>` fits.

The cloud resolves a group or task file only directly inside `groups/` or `tasks/`; a name that would end up in another folder is rejected even if it slipped past the pattern.

### Symbolic links

`cloud.yml`, `nodes.yml`, the files in `groups/` and `tasks/`, the folders `groups/`, `tasks/` and `secrets/`, and `secrets/vectora.env` must be real files and folders. A symbolic link, a Windows junction, a special file, or a file where a folder belongs is an error with the code `not-regular`:

- It is reported, never skipped silently. A link named like a configuration file (`groups/lobby.yml`) counts, a link with another name (`groups/notes.txt`) is ignored like any other file.
- It is never followed. A dangling link where `cloud.yml` belongs does not count as a missing file, so the cloud does not write default files through it.
- The secret store refuses a linked `secrets/` or `vectora.env` and writes nothing.

The home folder itself may be a link, for example a mounted volume. The rule exists because the cloud replaces a file by renaming a new file over it, which would silently turn a link into a plain file, and because a link could point a secret or configuration out of the home. All checks use `NOFOLLOW_LINKS` (`RegularPaths` in `net.vectora.cloud.config.io`).

Files in `groups/` and `tasks/` that start with `.` (editor swap files, the cloud's own temporary files) and files that do not end in `.yml` or `.yaml` are ignored. A `.yaml` file or a `.yml` file with an invalid name is an error, so a file that would otherwise be skipped silently is reported.

## Strict reading

People edit these files by hand, so typos must not slip through:

- An unknown field is an error, with its path, for example `cloud.yml: database.hostt (line 20): Unknown field "hostt".`
- A key that appears twice is an error.
- A required field that is missing or `null` (for example `scaling.static-services`, the keys of `process.restart`) is an error with the code `required` at its path.
- No guessing between types: `port: "25565"`, `port: 1.5`, `eula-accepted: yes` or `1`, and an unquoted `minecraft-version: 1.20` all fail.
- A second YAML document in the same file, more than 1 MiB, nesting deeper than 32 levels and very long strings fail.
- Problems in several files are reported together.

### Error format

Every problem is a `ConfigError` with file, field path, line (if known), a stable code and a message in English and German:

```
cloud.yml: proxy.port (line 13): Must be between 1 and 65535. [out-of-range]
cloud.yml: proxy.port (Zeile 13): Muss zwischen 1 und 65535 liegen.
```

Codes (`ErrorCode.code()`): `syntax`, `not-a-mapping`, `file-too-large`, `file-missing`, `file-access`, `invalid-file-name`, `schema-version-missing`, `schema-version-unsupported`, `unknown-field`, `duplicate-field`, `invalid-value`, `required`, `out-of-range`, `pattern-mismatch`, `min-greater-than-max`, `port-conflict`, `port-in-service-range`, `duplicate-entry`, `unknown-group`, `unknown-task`, `unknown-node`, `already-exists`, `not-allowed`, `catalog-kind-mismatch`, `software-version-mismatch`, `static-above-min`, `scale-down-not-below-up`, `api-tls-required`, `eula-required`, `empty`, `not-regular`, `jvm-flag-forbidden`, `jvm-flag-not-listed`, `invalid-origin`.

An error never contains the value that was wrong, only the limit, pattern or name involved. A secret pasted into the wrong field therefore cannot reach a log through its error message. Names taken from the input (an unknown field, group, task or node) are passed on unchanged only when they are valid names; otherwise control and non-ASCII characters become `?` and the text is cut to 64 characters, so it cannot break or forge a log line.

### Validation rules

Besides types and patterns, `ConfigValidator` checks the configuration as a whole:

- Ports are between 1 and 65535.
- The fixed ports of this node do not collide: proxy, Bedrock, legacy proxy (if enabled), API (if enabled), database (in `managed` and `docker` mode) and the node's channel port. The Bedrock port counts although it is UDP, because Geyser also opens TCP.
- None of these lie inside the node's `service-ports`.
- `heap.min` ≤ `heap.max`, `static-services` ≤ `min-services` ≤ `max-services` (`static-above-min`), `scale-down-below` < `scale-up-at` (`scale-down-not-below-up`), `service-ports.first` ≤ `last`.
- A node's `max-memory`, if set, lies between 512M and the largest size the cloud accepts.
- Every field of a node's `memory-reserve`, if set, lies between 1M and 64G (`out-of-range`).
- Tasks refer to existing groups and nodes; `node-id` refers to an existing node; node ids are unique.
- `fallback-group`, if set, is a valid group name and an existing group (`unknown-group`), so a group cannot be deleted while it is named there. Without the field the default `lobby` is used and need not exist; the proxies then find no server there.
- Catalog ids are unique per group and fit the software.
- `docker-image` only and always with `mode: docker`.
- An enabled API on a non-loopback address needs TLS.
- `setup-complete: true` needs `eula-accepted: true`.
- A group's `drain-timeout`, if set, lies between 5s and 10m (`out-of-range`).
- `process`: flag count, flag pattern and reserved flags, `stop-command` pattern, `stop-timeout` from 1 s to 600 s, `max-attempts` from 1 to 1000, `backoff` ≤ `max-backoff`.

Not checked here: whether a template folder or a catalog id exists. The template and download modules check that when they use them.

## Changing the configuration: `ConfigService`

```java
try (ConfigService config = ConfigService.open(CloudHome.of(Path.of("/opt/vectora")))) {
    config.addListener(change -> log.info("changed: " + change.files()));
    config.startWatching();
    config.apply(List.of(new SetServerWanted(ServerKind.MOD, false), new SetLanguage(Language.DE)));
}
```

`apply` runs the operations in order, validates the result as a whole, writes the changed files and then tells the listeners (`ConfigChanged` with the old and new snapshot, the files and the operations). If validation fails, it throws `ConfigException` with every error, writes nothing and keeps the current snapshot. Several operations in one call count as one change, so a new group and its task can be added together.

Operations in `net.vectora.cloud.config.operation`: `SetLanguage`, `SetNetworkName`, `AcceptEula`, `CompleteSetup`, `SetServerWanted`, `SetDatabaseMode`, `SetDatabase`, `SetProxy`, `SetLegacyProxy`, `SetApi`, `PutGroup`, `RemoveGroup`, `SetGroupHeap`, `SetGroupWanted`, `PutTask`, `RemoveTask`, `SetTaskScaling`, `PutNode`, `RemoveNode`. `ConfigOperation` is a plain interface; other modules add their own operations by implementing it.

The service is thread-safe. `snapshot()` never blocks; changes and reloads run one at a time. Listeners are called in order while the service holds its lock, so they see changes in the order they happened. They may read the snapshot and call `apply` again, but must return quickly. Such a nested `apply` returns its result at once; its event is queued and delivered after every listener has seen the change that triggered it, so no listener sees the second change before the first. An exception in a listener is logged and does not stop the others.

### Hand edits

`startWatching()` watches `cloud.yml`, `nodes.yml`, `groups/` and `tasks/`. If a watched folder is deleted, renamed or unmounted, its watch key becomes invalid. The watcher then creates `groups/` or `tasks/` again (as on the first start), watches it again and reloads. If the folder cannot be watched again (for example a file now has its name), listeners get `reloadFailed` with `file-access` for that folder, and the other folders stay watched. After a burst of changes has been quiet for 200 ms, all files are read again:

- valid and different: it becomes the current snapshot, and listeners get a `ConfigChanged` with origin `FILE`;
- invalid, or `cloud.yml`/`nodes.yml` deleted: the previous snapshot stays active, the errors are logged and listeners get `reloadFailed`;
- equal to the current snapshot (for example the cloud's own write): nothing happens.

The watcher is native on Linux and Windows. On macOS Java polls, so a change can take a few seconds to show up. `reload()` does the same on demand, for example from a console command.

## Atomic writes

Each file is replaced atomically (`AtomicFileWriter`): the new content goes to a hidden temporary file in the same folder, is flushed to disk, and is renamed over the old file with `ATOMIC_MOVE`. A crash before the rename leaves the old file untouched, and the temporary file is deleted.

Fallback: some network and FUSE file systems cannot rename atomically (`AtomicMoveNotSupportedException`). The writer then renames with `REPLACE_EXISTING` only. A crash at exactly that moment may leave the target missing; the complete new content is still in the temporary file next to it. On Windows a rename can fail for a moment while an editor or a virus scanner has the file open; the writer retries up to five times.

A change that touches several files is written file by file. If writing fails half way, `apply` throws, the snapshot in memory stays at the old state, and the next reload brings memory and disk together again.

## Schema versions and migrations

Every file has `schema-version`. The current version is 2 for task files and 1 for the other three formats.

- A file without `schema-version`, or with a version newer than the cloud knows, is an error.
- A file with an older version runs through one `ConfigMigration` per version step (on the raw YAML tree, so renamed or removed fields can be handled), is then read normally, and is rewritten in the current format when the service opens or reloads it. The old file is kept as `<file>.v<version>.bak`.

To change a format: raise `SCHEMA_VERSION` in the model record, change the record, and register a migration from the previous version in `ConfigFormat`. `ConfigFormat` refuses a chain with gaps.

## Secrets

`secrets/vectora.env` holds the secrets in `KEY=VALUE` lines. `cloud/dist/secrets/vectora.env.example` lists the keys.

### The `Secret` type

`Secret` wraps a secret value. `toString()` always returns `***`, the value is only reachable through `reveal()`, `equals` compares in constant time, and `hashCode` is a constant. Use it for every secret that leaves `vectora.env`.

### Format of `vectora.env`

`EnvFileParser` is strict:

- UTF-8, `\n` or `\r\n` line endings; a byte order mark is skipped.
- A line is blank, a comment (`#` after optional spaces or tabs) or `KEY=VALUE`.
- Keys match `[A-Z0-9_]+` (at most 128 characters) and appear once.
- The value is everything after the first `=`, literally. No quotes, escapes, `export`, inline comments or variable expansion. It may be empty.
- No multi-line values: control characters and Unicode line separators fail. A value must not start or end with whitespace (almost always a copy-and-paste mistake).
- At most 64 KiB and 2000 lines.

Errors name the line and the rule, never the content.

### Generation and storage

On the first start `SecretStore.ensureGenerated` creates every missing secret with `SecureRandom`:

| Key | Value |
|---|---|
| `VECTORA_FORWARDING_SECRET` | 48 characters `A-Z a-z 0-9` |
| `VECTORA_LEGACY_FORWARDING_SECRET` | 48 characters `A-Z a-z 0-9`, generated independently of `VECTORA_FORWARDING_SECRET` |
| `VECTORA_DB_PASSWORD` | 32 characters `A-Z a-z 0-9` |
| `VECTORA_DB_ROOT_PASSWORD` | 32 characters `A-Z a-z 0-9` |
| `VECTORA_MANAGEMENT_SECRET` | 40 characters `A-Z a-z 0-9` |
| `VECTORA_FLOODGATE_KEY_B64` | 16 random bytes (raw AES key), Base64 |

`VECTORA_DB_HOST`, `VECTORA_DB_PORT`, `VECTORA_DB_NAME` and `VECTORA_DB_USER` are not secrets; their values come from the `database` section of `cloud.yml`.

Rules of the store:

- An existing value is never overwritten. Missing keys are appended with a comment; empty entries (`KEY=`, as copied from the example) are filled in place.
- Comments and blank lines stay.
- The file is replaced atomically. The temporary file is restricted to the owner before the secrets are written into it.
- Permissions: on Linux and macOS the folder is `700` and the file `600`. On Windows the access list of folder and file is replaced by one entry that gives the owner full control, inherited by new files in the folder, and is then marked protected (`icacls /inheritance:d`, called by its full path in `System32`; Java's ACL API cannot set the flag). Entries an administrator later adds to a parent folder are therefore not inherited. Explicitly replacing the list (for example `icacls /reset` or "Replace all child object permissions") still overrides it, as it overrides any protected list. File systems without either model (FAT, some network shares) cannot be restricted.
- A malformed file is not touched; the error is reported.
- `SecretStore.put` sets one value the operator chose (the password of an external database) and replaces an existing entry in place. Values that do not fit on one line are refused before anything is written.

### Redaction

`RedactingFilter.of(secrets)` replaces every known secret value in a text with `***`, longest values first. Values shorter than four characters are skipped, because masking every `1` would destroy a log without protecting anything. The log filter of the node builds on it.

## Fuzz tests

Both parsers have Jazzer fuzz tests (see [architecture](architecture.md#fuzz-tests)):

- `EnvFileParserFuzzTest.parseFailsOnlyWithEnvFormatExceptionAndRoundTrips`: any input either fails with `EnvFormatException` or parses into a file whose rendering parses to the same lines.
- `ConfigYamlFuzzTest.loadAndValidateFailOnlyWithConfigErrors`: the first byte picks the file kind, the rest is the file. Any input either fails with `ConfigException` or loads, validates without an exception and writes back to an equal value.

Seed inputs live in `cloud/config/src/test/resources/net/vectora/cloud/config/…/*FuzzTestInputs/`. They are raw bytes, so a `.gitattributes` there switches off line-ending normalisation.

```sh
./gradlew :cloud:config:test --tests 'net.vectora.cloud.config.io.ConfigYamlFuzzTest.loadAndValidateFailOnlyWithConfigErrors' -Pfuzz -PfuzzMaxDuration=5m
```

## JVM flag allowlist

Groups can be written through the [REST API](api.md), so a stolen token must not turn into code execution on the node through `process.jvm-flags`. `JvmFlagPolicy` sorts every flag into one of three classes:

**Forbidden** (`jvm-flag-forbidden`): flags that load code, run commands, open remote access, write files outside the service or bypass a gate of the node.

- `@argfiles`, `-javaagent:`, `-agentpath:`, `-agentlib:`, `-Xrun`, `-Xdebug`, `-Xbootclasspath`, `--patch-module`, `--module-path`/`-p`, `--upgrade-module-path`, `--system`, `--limit-modules`, `-Xplugin`
- `-XX:+EnableDynamicAgentLoading`, `-XX:+EnableJVMCI`, `-XX:+UseJVMCICompiler`, `-XX:+EnableJVMCIProduct`
- `-XX:OnOutOfMemoryError=`, `-XX:OnError=`, `-XX:Flags=`, `-XX:VMOptionsFile=`, `-XX:CompileCommand(File)=`, `-XX:JVMCILibPath=`, `-XX:JVMCIPrintProperties`, `-XX:StartFlightRecording`, `-XX:FlightRecorderOptions`, `-XX:ErrorFile=`, `-XX:HeapDumpPath=`, `-XX:LogFile=`, `-XX:SharedArchiveFile=`, `-XX:AOTCache=`, `-XX:AOTConfiguration=`, `-XX:ArchiveClassesAtExit=`
- `-D` (names compared in lower case) for `java.system.class.loader`, `java.security.*`, `java.library.path`, `sun.boot.library.path`, `java.class.path`, `java.home`, `java.ext.dirs`, `java.endorsed.dirs`, `jdk.attach.allowAttachSelf`, `java.rmi.server.codebase`, `com.mojang.eula.agree`, the log4j configuration files, `fabric.addMods`, `fabric.gameJarPath`, `fabric.classPathGroups`, `fabric.remapClasspathFile` (Fabric loads jars from these paths), `jline.sh` and `jline.stty` (programs JLine 2 runs), `io.netty.native.workdir`, `io.netty.tmpdir` and `velocity.natives-tmpdir` (folders native libraries are loaded from), `org.bukkit.plugin.java.LibraryLoader.centralURL` (the Maven repository Paper loads plugin libraries from), and every property starting with `com.sun.management.jmxremote`, `jdk.module.`, `jdk.internal.` or `log4j2.script`

**Allowed:**

- boolean `-XX:+Name` / `-XX:-Name` (except the four above)
- `-XX:Name=value` for GC and sizing flags (`MaxGCPauseMillis`, the `G1…` flags, `MaxRAMPercentage`, `ShenandoahGCMode`, ...), value `[A-Za-z0-9.+-]{1,64}`
- `-Xss`, `-Xmn` with a size, `-Xrs`, `-Xnoclassgc`, `-Xint`, `-Xmixed`, `-Xbatch`, `-Xshare:auto|on|off`, `-server`, `--enable-preview`, assertions (`-ea`, `-da`, ...)
- `--add-opens`, `--add-exports`, `--add-reads`, `--add-modules`, `--enable-native-access`, `--sun-misc-unsafe-memory-access`, `--illegal-native-access`
- `-Xlog` to the console only (no `file=`)
- `-D` for `file.encoding`, `stdout.encoding`, `stderr.encoding`, `user.language`, `user.country`, `user.timezone`, `java.awt.headless`, `java.net.preferIPv4Stack`, `java.net.preferIPv6Addresses`, the Aikar flags (`using.aikars.flags`, `aikars.new.flags`), Paper switches (`disable.watchdog`, `IReallyKnowWhatIAmDoingISwear`), `log4j2.formatMsgNoLookups`, and properties starting with `paper.`, `velocity.`, `fabric.`, `vectora.`, `terminal.`, `jline.`, `io.netty.`, `net.kyori.`, `com.velocitypowered.`, `geyser.`, `floodgate.`, `spark.`, `jdk.virtualThreadScheduler.`. Under these prefixes the value must be plain, `[A-Za-z0-9_.,+-]{0,128}` without `..`: a path, a drive, a URL, `@` or `%` is not listed, because it could point a loader somewhere else. The exact names above may have any value.
- No other `-D`. A plugin's own switch such as `-Dmyplugin.debug=true` is not listed: the node cannot know which library on the class path reads a name and what it does with it (Paper's library loader, for example, takes its Maven repository from a property). Put such a switch into the group file by hand; it then stays and is reported as a warning (see below).

**Not listed** (`jvm-flag-not-listed`): everything else.

**Existing configurations.** Files written before the allowlist keep working. Only a change through the REST API is checked, and only for flags it adds: a flag that is already in the group (counted per occurrence) may stay, a new forbidden or unlisted flag is refused with the errors above. Flags in the files that are not allowed are logged as warnings when the node starts and after every reload, and `GET /api/v1/config/warnings` lists them. The console, the setup assistant and hand edits of the files are not refused, because whoever can use them can already change the node itself; their flags show up as warnings like the old ones.

## JSON API

The [REST API](api.md) uses the same records. `ConfigMappers.configure(JsonMapper.builder()).build()` gives a JSON mapper with the same names and the same strictness as the YAML files.
