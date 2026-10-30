# Template rendering (`cloud-template`)

`cloud/template` (package `net.vectora.cloud.template`) turns a template folder (`<home>/local/templates/<template>/default/`) into the folder of a service (`<home>/local/services/<id>` for a static one, `<home>/temp/services/<id>_<run>` for a dynamic one). The parameters of the API below still call the target `runtime`. It replaces the `{{VECTORA_*}}` placeholders, writes the files that come from secrets, and builds the proxy's server list from the wanted servers. The placeholders themselves are listed in [`templates/placeholders.md`](../../templates/placeholders.md).

The node carries a copy of every template in its jar and writes it into a home that does not have it, but only for servers that are wanted ([node.md](node.md#built-in-templates)); the repository keeps these templates in `templates/<Server>/`. The home keeps the template of a group in `local/templates/<template>/default/`, where `<template>` is the `template` value of the group (`Lobby-Server`, or a name of your own).

The module does not read `config/cloud.yml` or `secrets/` itself. The caller (later `cloud/node`) passes the values through the `PlaceholderValues` interface.

## API

| Type | Purpose |
|---|---|
| `TemplateInstaller.install(template, runtime, platform, values, eulaAccepted)` | The usual entry point: renders the template and writes the generated files for the platform. Returns a `RenderReport`. |
| `TemplateRenderer.render(template, runtime, values)` | Renders and copies the template files only. |
| `PlaceholderRenderer.render(text, file, values)` / `renderUtf8(bytes, file, values)` | Renders one text. |
| `PlaceholderValues` | Source of the values. `PlaceholderValues.of(map)`, `a.orElse(b)`, `none()`. |
| `ProxyServerList.of(backends, tryOrder, forcedHosts)` | Values for `VECTORA_PROXY_SERVERS`, `VECTORA_PROXY_TRY` and `VECTORA_PROXY_FORCED_HOSTS`. |
| `ProxyBackend(name, host, port, wanted)` | One backend as the proxy sees it. |
| `GeneratedFiles` | Writes `forwarding.secret`, Floodgate's `key.pem` and `eula.txt`. |
| `SpigotRestart` (package-private) | Turns Spigot's own restart off in the service's `spigot.yml` of Paper and PandaSpigot (see [Restarts are the node's job](#restarts-are-the-nodes-job)). |
| `ServerPlatform` | `VELOCITY`, `VELOCITY_LEGACY`, `PAPER`, `PANDASPIGOT`, `FABRIC` or `NONE`. Decides which files are generated and where. |
| `TemplateException` | Every rendering error. The message starts with `file:line`. |

Typical use:

```java
PlaceholderValues values = PlaceholderValues.of(secretsAndServiceValues)
        .orElse(ProxyServerList.of(backends, List.of("lobby"), forcedHosts).placeholderValues());
RenderReport report = TemplateInstaller.install(
        home.resolve("local/templates/Proxy/default"), home.resolve("local/services/proxy-1"),
        ServerPlatform.VELOCITY, values, eulaAccepted);
```

## Placeholder syntax

- `{{VECTORA_NAME}}` is replaced by the value of `VECTORA_NAME`. The name is `VECTORA_` followed by upper-case letters, digits and underscores, with no spaces inside the braces.
- The renderer is strict. A name without a value is an error, never an empty string. Every other `{{` is an error too: unclosed, lower case, with spaces, or a name outside `VECTORA_`. A rendered file therefore never contains a leftover placeholder.
- Errors name the file and line, for example `plugins/LuckPerms/config.yml:12: unknown placeholder {{VECTORA_DB_HOST}} (no value given)`. They never contain a value, because values include secrets.
- Values are inserted once and not scanned again.

### Allowed values

A value must not be able to change the structure of the file it lands in: a second YAML key, a new TOML table, another SQL statement. The renderer therefore checks every value before it inserts it and fails with a `TemplateException` that names the placeholder and the line, never the value:

- No value may contain a line break (`\n`, `\r`) or any other control character (tab, NUL, everything `Character.isISOControl` reports). The only exceptions are `VECTORA_PROXY_SERVERS` and `VECTORA_PROXY_FORCED_HOSTS`: they may span lines, but still contain no other control character. `ProxyServerList` builds both from checked names and hosts.
- Secrets, that is every placeholder whose name ends in `_SECRET` or `_PASSWORD` (today `VECTORA_FORWARDING_SECRET`, `VECTORA_LEGACY_FORWARDING_SECRET`, `VECTORA_MANAGEMENT_SECRET`, `VECTORA_DB_PASSWORD`; which of them a service gets depends on its platform, see [node](node.md#ids-folders-and-ports), and `VECTORA_DB_ROOT_PASSWORD` reaches no template), must match `[A-Za-z0-9._~+/=-]{16,}`. Alphanumeric and base64 secrets, as the cloud generates them, fit. Quotes, `#`, spaces and backslashes do not, so a secret can neither end a quoted string nor start a comment.
- Ports, every placeholder whose name ends in `_PORT`, must be a decimal number from 1 to 65535.

`VECTORA_FLOODGATE_KEY_B64` is no text placeholder; it must be base64 of exactly 16 bytes (see below).

### Writing a literal `{{`

Write four opening braces: `{{{{` becomes `{{`. To get the text `{{VECTORA_X}}` into a rendered file, write `{{{{VECTORA_X}}`. A single `{` and any `}}` need no escape.

No current template needs this. It is there for a future config format that uses double braces itself.

## Which files are rendered

Files with one of these extensions (case-insensitive) are rendered: `yml`, `yaml`, `toml`, `properties`, `json`, `conf`, `txt`, `cfg`, `ini`, `sql`, `bat`, `cmd`.

`bat` and `cmd` are on the list because the current start scripts (`start.bat`, `start-vectora-database.cmd`) carry `VECTORA_XMS`, `VECTORA_XMX` and `VECTORA_DB_PORT`. The scripts go away once the cloud starts the servers itself.

Every other file (images, jars, `.dat`, files without an extension) is copied byte for byte.

Rendered files keep their encoding and line endings:

- Text files must be valid UTF-8. A byte order mark is kept.
- Everything outside a placeholder stays as it is, including CRLF in `.bat` files.
- A multi-line value such as `VECTORA_PROXY_SERVERS` is written with the line ending of the file (its first line break, `\n` if it has none).

Markdown files (`*.md`, case-insensitive, in any folder of the template) are neither rendered nor copied and are listed in `RenderReport.skipped()`. They document the template and quote the placeholders by name, so rendering them would put real values, passwords included, into the service folder. No server reads a Markdown file.

## Template to service folder

In this section "service folder" is the target (`runtime` in the API): `local/services/<id>` or `temp/services/<id>_<run>`.

- Files that come from the template overwrite their copy in the service folder.
- Everything else in the service folder stays untouched: worlds, jars, logs, player data, LuckPerms files. Nothing is deleted. A file that is removed from a template therefore stays in existing service folders until someone deletes it.
- All text files are rendered in memory before the first write. An unknown placeholder or a broken file leaves the service folder as it was.
- Symbolic links and Windows junctions in the template are skipped, not followed, and listed in `RenderReport.skipped()`. The template folder itself may be a link.
- Symbolic links and Windows junctions inside the service folder are never written through. A link or junction where a folder or a target file should be is an error. The service folder itself may be a link. Java does not report a junction as a symbolic link but as "other"; both count as a link here.
- Target paths are plain relative paths. `..`, `.`, empty parts, absolute paths, drive letters, backslashes and control characters are rejected, and the resolved path must stay inside the service folder.
- The template and service folders must not contain each other.

## Generated files

| File | Platform | Source |
|---|---|---|
| `forwarding.secret` | `VELOCITY` | `VECTORA_FORWARDING_SECRET`, written as UTF-8 without a line break. Must follow the secret rule above (16 or more characters from `A-Z a-z 0-9 . _ ~ + / = -`). |
| `forwarding.secret` | `VELOCITY_LEGACY` | `VECTORA_LEGACY_FORWARDING_SECRET`, same rules. The install fails if it equals `VECTORA_FORWARDING_SECRET`: in `bungeeguard` mode Velocity sends it to the 1.8.8 backends in plain text. |
| `plugins/floodgate/key.pem` | `VELOCITY`, `PAPER` (not `VELOCITY_LEGACY` and `PANDASPIGOT`, which run no Floodgate yet), only if the template has a `plugins/floodgate/` folder | `VECTORA_FLOODGATE_KEY_B64`, decoded to the raw 16 bytes. Floodgate reads raw bytes despite the file name. |
| `config/floodgate/key.pem` | `FABRIC`, only if the template has a `config/floodgate/` folder | Same key. |
| `eula.txt` | `PAPER`, `PANDASPIGOT`, `FABRIC` | Only with `eulaAccepted = true`. Without consent nothing is written, and an existing `eula.txt` stays as it is. |
| `spigot.yml` | `PAPER`, `PANDASPIGOT` | Only when the node had to create or change it: `settings.restart-script: ''` and `settings.restart-on-crash: true`, see below. |
| `server.properties` | `PAPER`, `PANDASPIGOT`, `FABRIC` | Only when the node had to create or change it: `enable-command-block=false`, see [Command blocks are off](#command-blocks-are-off). |

The paths match the Floodgate configs in the templates (`key-file-name: key.pem` next to each `config.yml`). A template without Floodgate's folder runs no Floodgate (`TemplateInstaller.runsFloodgate`): it gets no key and does not need `VECTORA_FLOODGATE_KEY_B64`, and the node does not offer the key to it as a placeholder either. A `key.pem` an earlier start wrote is deleted once the template no longer has the folder (only a regular file reached without a link; the rest of the folder stays). The secrets are checked before anything is written. Each generated file is written to a new temporary file next to the target (`.vectora-<random>.tmp`), flushed to disk and then moved over the target. A secret file is therefore never truncated in place, never written through a hard link or symbolic link at the target, and never visible half-written. The temporary file of `forwarding.secret` and `key.pem` is created readable by the owner only: with POSIX permissions `rw-------` from the start, on Windows with an access list that allows only the owner. A failed write leaves the old file and no temporary file behind.

`NONE` (the database template) gets no generated files.

### Restarts are the node's job

Spigot, Paper and PandaSpigot can restart themselves. `settings.restart-script` in `spigot.yml` names a script that the server runs from a shutdown hook (`sh <script>`, on Windows `cmd /c start <script>`) on `/restart`, and after the watchdog has ended a hung server if `settings.restart-on-crash` is `true`. The server checks only whether the first word of the script is an existing file. If it is, a second server starts outside the node: without a service token, missing from the proxy's list, and maybe on the same port and world while the node restarts the service as well. If it is not, the server just stops. When the keys are missing, Spigot adds `restart-on-crash: true` and `restart-script: ./start.sh` on its first start.

So every Paper and PandaSpigot template sets `restart-script: ''` and `restart-on-crash: true`, and the node enforces both after rendering, also for an operator's own template. An empty path is never a file: `/restart` and a watchdog crash only stop the server, the node counts the exit as a crash (see [node](node.md#life-cycle)) and starts it again if `process.restart.on-crash` is on and the service is still wanted.

`restart-on-crash` stays `true` on purpose. With the empty script it no longer means "restart", only "stop": the server calls its restart routine, finds no script and shuts down. On PandaSpigot (Spigot 1.8.8) that is the only way the watchdog ends a hung server at all; with `false` it logs a thread dump and leaves the hung process running, and the node would keep it as RUNNING. Paper's watchdog stops a hung server either way.

- Only these two keys directly under the top-level `settings:` block change. Everything else in the file stays as it is, keys with the same name deeper down too. A value spread over several lines is replaced as a whole.
- A missing key is added at the top of the block, with the block's indent. A file without `settings:` gets the block appended; a service folder without `spigot.yml` gets a file with just that block, and the server adds every other setting on its first start.
- `settings` as a flow mapping (`settings: {...}`), with an anchor or tag, twice in the file, or a file that is a flow mapping is refused with a `TemplateException` and the service does not start. So is a top-level key or a key directly under `settings` that is not plain or quoted without escapes (an explicit key `? restart-script`, `&anchor key:`, `!!str key:`, `"restart-script":`, a list item), because YAML keeps the last of two equal keys and such a spelling could bring the old value back after the fixed line. The message names `spigot.yml` and the line; fix the template (or the runtime file, if the template has none) to plain `key: value` lines. Files that Spigot writes itself never hit this. The node does not guess at a layout it cannot edit safely.
- The file is only written when something changes; it then appears in `RenderReport.generated()`. The shipped templates are right already, so `RealTemplatesTest` fails when the node would have to change one of them.
- Enforcement rather than a warning, because a warning would leave the second server possible, and because Spigot itself writes the dangerous defaults into any `spigot.yml` without the keys. The service folder belongs to the node in the same way as `eula.txt`; there is no reason under the cloud to let a server restart itself.

Velocity and Fabric have no such setting. Paper's own files (`config/paper-global.yml`, `config/paper-world-defaults.yml`) have none either.

### Command blocks are off

A command block runs commands with the power of the console for whoever can reach it. On the network they do not exist, and there is no setting, placeholder or template that turns them back on.

- After rendering, `TemplateInstaller` sets `enable-command-block=false` in the runtime `server.properties` of every `PAPER`, `PANDASPIGOT` and `FABRIC` service (`CommandBlockSetting`). That happens on every start, also for an operator's own template and after an operator edited the file in the service folder.
- Keys are found the way the server reads the file (`java.util.Properties`): every spelling of the key counts (escapes such as `enable\-command\-block`, `:` or a blank as separator, a value continued over several lines). Every occurrence is replaced by one line `enable-command-block=false` in place of the first. A file without the key gets the line at the end, a service folder without the file gets a file with just that line. The result is read back with `Properties`; a file that cannot be read as properties (a malformed `\u` escape) is refused with a `TemplateException`, and the service does not start.
- Any value other than `false` is overwritten and reported in `RenderReport.warnings()`. The node logs it as a warning with the service id; the value itself is not logged.
- The file is read and written byte for byte (ISO-8859-1), so every other line keeps its bytes and its line break, a UTF-8 `motd` included. It is written only when something changes and then appears in `RenderReport.generated()`.
- Every shipped template already says `enable-command-block=false`. `RealTemplatesTest.noTemplateShipsCommandBlocks` fails when a template turns command blocks on or the node would have to change one; `CommandBlockSettingFuzzTest` checks that any file the server can read ends with command blocks off.

The setting alone does not remove command blocks that are already in a world, and the server still lets players place them. That part is done by the bridges ([bridges.md](bridges.md#command-blocks)).

## Proxy server list

`ProxyServerList.of(backends, tryOrder, forcedHosts)` takes every backend the cloud knows, each with its `wanted` flag. Only wanted servers appear in the output:

| Placeholder | Format | Order |
|---|---|---|
| `VECTORA_PROXY_SERVERS` | one line per server: `lobby = "127.0.0.1:25566"` | by name |
| `VECTORA_PROXY_TRY` | `"lobby", "survival"` (goes inside `try = [ ... ]`) | as given, repeats dropped |
| `VECTORA_PROXY_FORCED_HOSTS` | one line per host: `"creative.example.net" = [ "creative" ]` | by host; servers per host as given |

An unwanted server is dropped from the try order and from every forced host. A forced host left without a server is dropped. The output does not depend on the iteration order of the collections passed in.

Names must be 1 to 64 letters, digits, `_` or `-`, and unique ignoring case. Hosts are host names or IP addresses (IPv6 is put in brackets). Forced hosts are plain host names. A try entry or forced host that names an unknown server is an error. These checks keep anything from breaking out of the TOML quoting.

## Tests

- `RealTemplatesTest` renders every real `templates/<Server>/` with test values into a temporary folder. Afterwards no file may contain `{{`. Every YAML and JSON file must parse with Jackson, every `.properties` file with `java.util.Properties`, and every TOML file must pass a plausibility check (no TOML library in the build). The test also checks that a proxy without the survival server never mentions it, checks that the rendered `spigot.yml` of every Paper and PandaSpigot template turns Spigot's restart off without the node having to change it, checks that no template turns command blocks on, and compares the part of `velocity.toml` from `[servers]` up to `[advanced]` for 1, 3 and 6 wanted servers with the golden files in `src/test/resources/net/vectora/cloud/template/golden/`. When a template change is intended, the actual output lands in `build/golden-actual/` for review.
- `PlaceholderRendererFuzzTest` is a Jazzer target: arbitrary bytes may only end in a `TemplateException`, and text without `{{` must come back byte for byte. Seeds are in `PlaceholderRendererFuzzTestInputs/`. A `.gitattributes` next to that folder marks them binary, so git keeps their CRLF and invalid UTF-8. Local run:

  ```sh
  ./gradlew :cloud:template:test --tests 'net.vectora.cloud.template.PlaceholderRendererFuzzTest.renderFailsOnlyWithTemplateException' -Pfuzz -PfuzzMaxDuration=2m
  ```

Tests that need symbolic links are skipped where the OS does not allow creating them (Windows without developer mode). The junction tests run on Windows only and create the junction with `mklink /J`, which needs no extra rights.
