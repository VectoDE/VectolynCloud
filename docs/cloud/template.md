# Template rendering (`cloud-template`)

`VectoraCloud/cloud-template` (package `net.vectora.cloud.template`) turns `Server-Network/<Server>/template/` into the server's `runtime/` folder. It replaces the `{{VECTORA_*}}` placeholders, writes the files that come from secrets, and builds the proxy's server list from the wanted servers. The placeholders themselves are listed in [`Server-Network/placeholders.md`](../../Server-Network/placeholders.md).

The module does not read `cloud.yml` or `secrets/` itself. The caller (later `cloud-node`) passes the values through the `PlaceholderValues` interface.

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
| `ServerPlatform` | `VELOCITY`, `PAPER`, `FABRIC` or `NONE`. Decides which files are generated and where. |
| `TemplateException` | Every rendering error. The message starts with `file:line`. |

Typical use:

```java
PlaceholderValues values = PlaceholderValues.of(secretsAndServiceValues)
        .orElse(ProxyServerList.of(backends, List.of("lobby"), forcedHosts).placeholderValues());
RenderReport report = TemplateInstaller.install(
        network.resolve("Proxy/template"), network.resolve("Proxy/runtime"),
        ServerPlatform.VELOCITY, values, eulaAccepted);
```

## Placeholder syntax

- `{{VECTORA_NAME}}` is replaced by the value of `VECTORA_NAME`. The name is `VECTORA_` followed by upper-case letters, digits and underscores, with no spaces inside the braces.
- The renderer is strict. A name without a value is an error, never an empty string. Every other `{{` is an error too: unclosed, lower case, with spaces, or a name outside `VECTORA_`. A rendered file therefore never contains a leftover placeholder.
- Errors name the file and line, for example `plugins/LuckPerms/config.yml:12: unknown placeholder {{VECTORA_DB_HOST}} (no value given)`. They never contain a value, because values include secrets.
- Values are inserted once and not scanned again.

### Writing a literal `{{`

Write four opening braces: `{{{{` becomes `{{`. To get the text `{{VECTORA_X}}` into a rendered file, write `{{{{VECTORA_X}}`. A single `{` and any `}}` need no escape.

No current template needs this. It is there for a future config format that uses double braces itself.

## Which files are rendered

Files with one of these extensions (case-insensitive) are rendered: `yml`, `yaml`, `toml`, `properties`, `json`, `conf`, `txt`, `cfg`, `ini`, `sql`, `md`, `bat`, `cmd`.

`bat` and `cmd` are on the list because the current start scripts (`start.bat`, `start-vectora-database.cmd`) carry `VECTORA_XMS`, `VECTORA_XMX` and `VECTORA_DB_PORT`. The scripts go away once the cloud starts the servers itself.

Every other file (images, jars, `.dat`, files without an extension) is copied byte for byte.

Rendered files keep their encoding and line endings:

- Text files must be valid UTF-8. A byte order mark is kept.
- Everything outside a placeholder stays as it is, including CRLF in `.bat` files.
- A multi-line value such as `VECTORA_PROXY_SERVERS` is written with the line ending of the file (its first line break, `\n` if it has none).

`README.md` directly in a template folder is not copied. It documents the template and quotes the placeholders by name, so rendering it would put real values, passwords included, into `runtime/`.

## Template to runtime

- Files that come from the template overwrite their copy in `runtime/`.
- Everything else in `runtime/` stays untouched: worlds, jars, logs, player data, LuckPerms files. Nothing is deleted. A file that is removed from a template therefore stays in existing runtime folders until someone deletes it.
- All text files are rendered in memory before the first write. An unknown placeholder or a broken file leaves `runtime/` as it was.
- Symbolic links in the template are skipped and listed in `RenderReport.skipped()`.
- Symbolic links inside `runtime/` are never written through. A link where a folder or a target file should be is an error. The `runtime/` folder itself may be a link.
- Target paths are plain relative paths. `..`, `.`, empty parts, absolute paths, drive letters, backslashes and control characters are rejected, and the resolved path must stay inside `runtime/`.
- The template and runtime folders must not contain each other.

## Generated files

| File | Platform | Source |
|---|---|---|
| `forwarding.secret` | `VELOCITY` | `VECTORA_FORWARDING_SECRET`, written as UTF-8 without a line break. Must not be blank or contain a line break. |
| `plugins/floodgate/key.pem` | `VELOCITY`, `PAPER` | `VECTORA_FLOODGATE_KEY_B64`, decoded to the raw 16 bytes. Floodgate reads raw bytes despite the file name. |
| `config/floodgate/key.pem` | `FABRIC` | Same key. |
| `eula.txt` | `PAPER`, `FABRIC` | Only with `eulaAccepted = true`. Without consent nothing is written, and an existing `eula.txt` stays as it is. |

The paths match the Floodgate configs in the templates (`key-file-name: key.pem` next to each `config.yml`). The secrets are checked before anything is written. On file systems with POSIX permissions, `forwarding.secret` and `key.pem` are readable by the owner only.

`NONE` (the database template) gets no generated files.

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

- `RealTemplatesTest` renders every real `Server-Network/*/template/` with test values into a temporary folder. Afterwards no file may contain `{{`. Every YAML and JSON file must parse with Jackson, every `.properties` file with `java.util.Properties`, and every TOML file must pass a plausibility check (no TOML library in the build). The test also checks that a proxy without the survival server never mentions it, and compares the part of `velocity.toml` from `[servers]` up to `[advanced]` for 1, 3 and 6 wanted servers with the golden files in `src/test/resources/net/vectora/cloud/template/golden/`. When a template change is intended, the actual output lands in `build/golden-actual/` for review.
- `PlaceholderRendererFuzzTest` is a Jazzer target: arbitrary bytes may only end in a `TemplateException`, and text without `{{` must come back byte for byte. Seeds are in `PlaceholderRendererFuzzTestInputs/`. A `.gitattributes` next to that folder marks them binary, so git keeps their CRLF and invalid UTF-8. Local run:

  ```sh
  ./gradlew :VectoraCloud:cloud-template:test --tests 'net.vectora.cloud.template.PlaceholderRendererFuzzTest.renderFailsOnlyWithTemplateException' -Pfuzz -PfuzzMaxDuration=2m
  ```

Tests that need symbolic links are skipped where the OS does not allow creating them (Windows without developer mode).
