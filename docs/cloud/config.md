# VectoraCloud configuration

Module `VectoraCloud/cloud-config`, package `net.vectora.cloud.config`. This is the one configuration layer of the cloud: the console, the setup assistant, hand-edited files and later the REST API and the dashboard all read and change the configuration through it.

## Files in the cloud home

In the repository checkout the cloud home is `Server-Network/`.

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
servers:                     # the "wanted" switch per server; a missing entry counts as false
  proxy: true
  lobby: true
  survival: true
  creative: true
  mod: true
  end: true
  database: true
  minigame: true
proxy:
  bind: 0.0.0.0
  port: 25565
  bedrock-port: 19132        # Geyser, UDP plus TCP
legacy-proxy:                # second Velocity (bungeeguard) for the 1.8.8 groups
  enabled: false
  port: 25564
database:
  mode: managed              # managed, docker, external or h2 (tests only)
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
catalog:                     # plugins and mods from the download catalog
  - id: luckperms
    kind: transitional       # plugin, mod or transitional
  - id: vectora-paper
    kind: plugin
```

`transitional` marks the third-party plugins the cloud installs until Vectora's own replacement is ready (LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard, Chunky, spark, ProxyPortal). `mod` entries are only allowed with `fabric`, `plugin` entries only without it. `pandaspigot` only runs `1.8.8`.

### `tasks/<name>.yml`

```yaml
schema-version: 1
group: lobby                 # must exist in groups/
mode: dynamic                # static keeps min-services running; dynamic scales
scaling:
  min-services: 1            # 0 to 1000
  max-services: 4            # 1 to 1000, at least min-services
  players-per-service: 50
  scale-up-at: 80            # percent of players-per-service
  scale-down-after: 5m       # s, m or h; at most 7 days
  cooldown: 60s
nodes: [node-1]              # empty: any node
```

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
```

### Names

Group, task and node names match `[a-z0-9][a-z0-9_-]{0,31}` and must not be a Windows device name (`con`, `prn`, `aux`, `nul`, `com0` to `com9`, `lpt0` to `lpt9`); `console` or `con1` are fine. Group and task names are the file names, so they cannot disagree with the file. Only lower case is allowed because Windows and macOS do not tell `Lobby.yml` from `lobby.yml`. The rule is the same as for `GroupName` and `NodeId` in `Vectora-Protocol` (1 to 32 characters, lower case, no device names), so every configured name can be sent. A service id there (`ServiceId`) may have up to 64 characters, so that `<group>-<index>` fits.

The cloud resolves a group or task file only directly inside `groups/` or `tasks/`; a name that would end up in another folder is rejected even if it slipped past the pattern.

Files in `groups/` and `tasks/` that start with `.` (editor swap files, the cloud's own temporary files) and files that do not end in `.yml` or `.yaml` are ignored. A `.yaml` file or a `.yml` file with an invalid name is an error, so a file that would otherwise be skipped silently is reported.

## Strict reading

People edit these files by hand, so typos must not slip through:

- An unknown field is an error, with its path, for example `cloud.yml: database.hostt (line 20): Unknown field "hostt".`
- A key that appears twice is an error.
- No guessing between types: `port: "25565"`, `port: 1.5`, `eula-accepted: yes` or `1`, and an unquoted `minecraft-version: 1.20` all fail.
- A second YAML document in the same file, more than 1 MiB, nesting deeper than 32 levels and very long strings fail.
- Problems in several files are reported together.

### Error format

Every problem is a `ConfigError` with file, field path, line (if known), a stable code and a message in English and German:

```
cloud.yml: proxy.port (line 13): Must be between 1 and 65535. [out-of-range]
cloud.yml: proxy.port (Zeile 13): Muss zwischen 1 und 65535 liegen.
```

Codes (`ErrorCode.code()`): `syntax`, `not-a-mapping`, `file-too-large`, `file-missing`, `file-access`, `invalid-file-name`, `schema-version-missing`, `schema-version-unsupported`, `unknown-field`, `duplicate-field`, `invalid-value`, `required`, `out-of-range`, `pattern-mismatch`, `min-greater-than-max`, `port-conflict`, `port-in-service-range`, `duplicate-entry`, `unknown-group`, `unknown-task`, `unknown-node`, `already-exists`, `not-allowed`, `catalog-kind-mismatch`, `software-version-mismatch`, `api-tls-required`, `eula-required`, `empty`.

An error never contains the value that was wrong, only the limit, pattern or name involved. A secret pasted into the wrong field therefore cannot reach a log through its error message. Names taken from the input (an unknown field, group, task or node) are passed on unchanged only when they are valid names; otherwise control and non-ASCII characters become `?` and the text is cut to 64 characters, so it cannot break or forge a log line.

### Validation rules

Besides types and patterns, `ConfigValidator` checks the configuration as a whole:

- Ports are between 1 and 65535.
- The fixed ports of this node do not collide: proxy, Bedrock, legacy proxy (if enabled), API (if enabled), database (in `managed` and `docker` mode) and the node's channel port. The Bedrock port counts although it is UDP, because Geyser also opens TCP.
- None of these lie inside the node's `service-ports`.
- `heap.min` ≤ `heap.max`, `min-services` ≤ `max-services`, `service-ports.first` ≤ `last`.
- Tasks refer to existing groups and nodes; `node-id` refers to an existing node; node ids are unique.
- Catalog ids are unique per group and fit the software.
- `docker-image` only and always with `mode: docker`.
- An enabled API on a non-loopback address needs TLS.
- `setup-complete: true` needs `eula-accepted: true`.

Not checked here: whether a template folder or a catalog id exists. The template and download modules check that when they use them.

## Changing the configuration: `ConfigService`

```java
try (ConfigService config = ConfigService.open(CloudHome.of(Path.of("Server-Network")))) {
    config.addListener(change -> log.info("changed: " + change.files()));
    config.startWatching();
    config.apply(List.of(new SetServerWanted(ServerKind.MOD, false), new SetLanguage(Language.DE)));
}
```

`apply` runs the operations in order, validates the result as a whole, writes the changed files and then tells the listeners (`ConfigChanged` with the old and new snapshot, the files and the operations). If validation fails, it throws `ConfigException` with every error, writes nothing and keeps the current snapshot. Several operations in one call count as one change, so a new group and its task can be added together.

Operations in `net.vectora.cloud.config.operation`: `SetLanguage`, `SetNetworkName`, `AcceptEula`, `CompleteSetup`, `SetServerWanted`, `SetDatabaseMode`, `SetDatabase`, `SetProxy`, `SetLegacyProxy`, `SetApi`, `PutGroup`, `RemoveGroup`, `SetGroupHeap`, `PutTask`, `RemoveTask`, `SetTaskScaling`, `PutNode`, `RemoveNode`. `ConfigOperation` is a plain interface; other modules add their own operations by implementing it.

The service is thread-safe. `snapshot()` never blocks; changes and reloads run one at a time. Listeners are called in order while the service holds its lock, so they see changes in the order they happened. They may read the snapshot and call `apply` again, but must return quickly. An exception in a listener is logged and does not stop the others.

### Hand edits

`startWatching()` watches `cloud.yml`, `nodes.yml`, `groups/` and `tasks/`. After a burst of changes has been quiet for 200 ms, all files are read again:

- valid and different: it becomes the current snapshot, and listeners get a `ConfigChanged` with origin `FILE`;
- invalid, or `cloud.yml`/`nodes.yml` deleted: the previous snapshot stays active, the errors are logged and listeners get `reloadFailed`;
- equal to the current snapshot (for example the cloud's own write): nothing happens.

The watcher is native on Linux and Windows. On macOS Java polls, so a change can take a few seconds to show up. `reload()` does the same on demand, for example from a console command.

## Atomic writes

Each file is replaced atomically (`AtomicFileWriter`): the new content goes to a hidden temporary file in the same folder, is flushed to disk, and is renamed over the old file with `ATOMIC_MOVE`. A crash before the rename leaves the old file untouched, and the temporary file is deleted.

Fallback: some network and FUSE file systems cannot rename atomically (`AtomicMoveNotSupportedException`). The writer then renames with `REPLACE_EXISTING` only. A crash at exactly that moment may leave the target missing; the complete new content is still in the temporary file next to it. On Windows a rename can fail for a moment while an editor or a virus scanner has the file open; the writer retries up to five times.

A change that touches several files is written file by file. If writing fails half way, `apply` throws, the snapshot in memory stays at the old state, and the next reload brings memory and disk together again.

## Schema versions and migrations

Every file has `schema-version`. The current version of all four formats is 1.

- A file without `schema-version`, or with a version newer than the cloud knows, is an error.
- A file with an older version runs through one `ConfigMigration` per version step (on the raw YAML tree, so renamed or removed fields can be handled), is then read normally, and is rewritten in the current format when the service opens or reloads it. The old file is kept as `<file>.v<version>.bak`.

To change a format: raise `SCHEMA_VERSION` in the model record, change the record, and register a migration from the previous version in `ConfigFormat`. `ConfigFormat` refuses a chain with gaps.

## Secrets

`secrets/vectora.env` holds the secrets in `KEY=VALUE` lines. `Server-Network/secrets/vectora.env.example` lists the keys.

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
| `VECTORA_DB_PASSWORD` | 32 characters `A-Z a-z 0-9` |
| `VECTORA_DB_ROOT_PASSWORD` | 32 characters `A-Z a-z 0-9` |
| `VECTORA_MANAGEMENT_SECRET` | 40 characters `A-Z a-z 0-9` |
| `VECTORA_FLOODGATE_KEY_B64` | 16 random bytes (raw AES key), Base64 |

`VECTORA_DB_HOST`, `VECTORA_DB_PORT`, `VECTORA_DB_NAME` and `VECTORA_DB_USER` are not secrets; their values come from the `database` section of `cloud.yml`.

Rules of the store:

- An existing value is never overwritten. Missing keys are appended with a comment; empty entries (`KEY=`, as copied from the example) are filled in place.
- Comments and blank lines stay.
- The file is replaced atomically. The temporary file is restricted to the owner before the secrets are written into it.
- Permissions: on Linux and macOS the folder is `700` and the file `600`. On Windows the access list of folder and file is replaced by one entry that gives the owner full control, inherited by new files in the folder. Windows does not mark this list as protected, so an administrator who later re-applies inheritance on a parent folder can widen it again. File systems without either model (FAT, some network shares) cannot be restricted.
- A malformed file is not touched; the error is reported.

### Redaction

`RedactingFilter.of(secrets)` replaces every known secret value in a text with `***`, longest values first. Values shorter than four characters are skipped, because masking every `1` would destroy a log without protecting anything. The log filter of the node builds on it.

## Fuzz tests

Both parsers have Jazzer fuzz tests (see [architecture](architecture.md#fuzz-tests)):

- `EnvFileParserFuzzTest.parseFailsOnlyWithEnvFormatExceptionAndRoundTrips`: any input either fails with `EnvFormatException` or parses into a file whose rendering parses to the same lines.
- `ConfigYamlFuzzTest.loadAndValidateFailOnlyWithConfigErrors`: the first byte picks the file kind, the rest is the file. Any input either fails with `ConfigException` or loads, validates without an exception and writes back to an equal value.

Seed inputs live in `VectoraCloud/cloud-config/src/test/resources/net/vectora/cloud/config/…/*FuzzTestInputs/`. They are raw bytes, so a `.gitattributes` there switches off line-ending normalisation.

```sh
./gradlew :VectoraCloud:cloud-config:test --tests 'net.vectora.cloud.config.io.ConfigYamlFuzzTest.loadAndValidateFailOnlyWithConfigErrors' -Pfuzz -PfuzzMaxDuration=5m
```

## JSON API

The API (stage 2, PR 15) uses the same records. `ConfigMappers.configure(JsonMapper.builder()).build()` gives a JSON mapper with the same names and the same strictness as the YAML files.
