# Downloads

`cloud/download` (`net.vectora.cloud.download`) fetches server software, plugins, mods and the MariaDB server, checks every file against a checksum and keeps it in a content-addressed cache. `software.lock.yml` records exactly which file each name stands for. The `jre` package picks the Java runtime for each server group and installs Temurin from Adoptium where no fitting system Java exists ([JRE management](#jre-management)).

The module uses the JDK `HttpClient` and Jackson 3. It has no other dependencies.

## Packages

| Package | Content |
|---|---|
| `http` | `DownloadClient` (HTTPS only, host allowlist, redirects, limits, retries), `HttpTransport` with the JDK implementation |
| `provider` | `ArtifactProvider` and one implementation per download API, `ProviderRegistry` |
| `cache` | `ArtifactCache` (`cache/artifacts/<sha256>`) and `ArtifactDownloader` (the verified download flow) |
| `lock` | `SoftwareLock`, `LockedArtifact`, `SoftwareLockFile` (YAML) |
| `catalog` | `ArtifactCatalog`, read from `catalog.yml` on the class path |
| `archive` | `SafeExtractor` for zip and tar.gz, `TarReader` |
| `jre` | `JavaRequirement` (which Java a group gets), `SystemJavaFinder`, `JreInstaller` (`<home>/jre/`), `JreManager` (entry point for `cloud/node`), `JavaRuntime` |
| `hash` | `Checksum`, `HashAlgorithm`, `MultiDigest` |

## HTTP rules

- Only `https://` on port 443, without user info. Plain HTTP is refused before anything is sent, also as a redirect target.
- Every provider has an allowlist of exact host names. The first URL and every redirect target must be on it. At most 5 redirects.
- Every request has a size limit. A Content-Length above the limit is refused at once, and the body is counted while it is read. API responses are limited to 16 MiB. A download is limited to the size the source announces, otherwise to 2 GiB.
- Timeouts: 15 s to connect, 30 s until the response headers arrive, 15 min per attempt in total. A watchdog closes a stalled connection when that time is up.
- Up to 3 attempts with exponential backoff (1 s, 2 s, at most 10 s) after network errors and 5xx answers. 4xx answers are not retried, and neither are a refused host or an exceeded limit.
- 429 (Too Many Requests) and 503 count as rate limits and get up to 2 retries of their own. The client waits as long as `Retry-After` asks: whole seconds (at most nine digits) or an HTTP date such as `Wed, 28 Oct 2026 12:00:05 GMT`, measured against the system clock in UTC. A date in the past means no wait. Without the header, or with a value it cannot read, it uses the backoff above. If `Retry-After` asks for more than 60 s, the request fails at once with a message that names the host and the requested wait, without the path or query of the URL.
- User-Agent: `VectoraCloud/<version> (+https://github.com/VectoDE/VectoraCloud)`. The version comes from `Implementation-Version` in the jar manifest, which `vectora.java-conventions` sets for every module jar; outside a jar (tests, IDE) it is `dev`. PaperMC and Modrinth ask for a User-Agent that names the application and a contact.

## Providers

All providers implement `ArtifactProvider.resolve(ArtifactRequest) → ResolvedArtifact`. A resolved artifact has a coordinate (provider, project, version, build), the HTTPS URL, the file name, the checksum (empty for trust on first use), the size if known and the Java minimum if the source states one.

| Id | API | Hosts | Checksum | Request fields |
|---|---|---|---|---|
| `papermc` | PaperMC Fill v3: `/v3/projects/{p}`, `/versions/{v}`, `/versions/{v}/builds?channel=STABLE`, `/builds/{b}` | `fill.papermc.io`, `fill-data.papermc.io` | sha256 | `project` (`paper`, `velocity`), `version` (newest version with a stable build if empty), `build`, `channel`. Returns `java.version.minimum`. |
| `fabric` | Fabric-Meta v2: `/versions/loader`, `/versions/installer`, `/versions/loader/{mc}/{loader}`, `/versions/loader/{mc}/{loader}/{installer}/server/jar` | `meta.fabricmc.net` | none, trust on first use | `gameVersion`, options `loader` and `installer` (newest stable if empty) |
| `mojang` | `piston-meta.mojang.com/mc/game/version_manifest_v2.json` and the version JSON | `piston-meta.mojang.com`, `piston-data.mojang.com`, `launcher.mojang.com`, `launchermeta.mojang.com` | sha1 (Mojang has nothing stronger) | `version` (newest release if empty). Returns `javaVersion.majorVersion`; `MojangProvider.javaMajorVersion(mc)` returns only that. The version JSON is checked against the SHA-1 in the manifest. |
| `geysermc` | GeyserMC download API v2: `/v2/projects/{p}`, `/versions/{v}`, `/builds/{b}`, `/downloads/{key}` | `download.geysermc.org` | sha256 | `project` (`geyser`, `floodgate`), `platform` = download key (`velocity`, `spigot`, ...), `version`, `build` |
| `pandaspigot` | Same API style at `downloads.hpfxd.com/v2` | `downloads.hpfxd.com` | sha256 | `version` (`1.8.8`), `build`. Download key `paperclip`. The build list is unsorted, so the highest number is taken. |
| `modrinth` | `/v2/project/{slug}/version?loaders=[..]&game_versions=[..]&include_changelog=false` | `api.modrinth.com`, `cdn.modrinth.com` | sha512 (sha1 is also listed; the stronger one is used) | `project` (slug), `platform` = loader, `gameVersion`, `version` (exact `version_number`), `channel` (`release` by default) |
| `hangar` | `/api/v1/projects/{slug}/versions?platform=..&platformVersion=..&channel=Release`, `/versions/{name}` | `hangar.papermc.io`, `hangarcdn.papermc.io` | sha256 | `project`, `platform` (`PAPER`, `VELOCITY`, `WATERFALL`), `gameVersion`, `version`, `channel`. Versions that only link to an external site have no checksum and are refused. |
| `mariadb` | `downloads.mariadb.org/rest-api/mariadb/`, `/{series}/latest/`, `/{release}/` | `downloads.mariadb.org`, `archive.mariadb.org` | sha512 (sha1, sha256 and sha512 are listed) | `version` (`11.8.9`, `11.8` or empty for the newest stable LTS series), `system` |
| `adoptium` | Adoptium API v3: `/v3/assets/latest/{feature}/hotspot`, `/v3/assets/release_name/eclipse/{release}` | `api.adoptium.net`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com` | sha256 | `project` (`temurin`), `version` (feature version 8 to 99, required), `system`, options `image-type` (`jre` or `jdk`) and `release` (for example `jdk-21.0.13+9`) |

Notes:

- **MariaDB** resolves only. Windows x64 gets the zip, Linux x86_64 with glibc the `linux-systemd` tar.gz. Every other platform gets an `ArtifactNotAvailableException` that names the platform and points to the Docker mode or an external server. The API lists `http://` URLs. They are rewritten to `https://` and get `?mirror=archive`, because the default redirect goes to a geo-selected third-party mirror (`mirror.wtnet.de` from Germany) that no allowlist can know in advance. With `mirror=archive` the redirect goes to `archive.mariadb.org`.
- **Fabric** publishes no checksum for the server launcher. See trust on first use below.
- **Adoptium** resolves Eclipse Temurin (HotSpot, normal heap). Windows gets the zip (not the MSI), Linux and macOS the tar.gz; musl Linux maps to Adoptium's `alpine-linux`. Supported are x64 and aarch64 on Windows, Linux and macOS; anything else gets an `ArtifactNotAvailableException` that says to install Java by hand. Without `image-type` a JRE is preferred and a JDK is taken only if Adoptium has no JRE for that platform (Java 8 on macOS aarch64 is such a case). The coordinate is `adoptium:temurin:<openjdk version>#<os>-<arch>-<image type>`, so each platform is its own artifact. The download link is the GitHub release of `adoptium/temurin<N>-binaries`, which redirects to `release-assets.githubusercontent.com`. Checked on 2026-10-28: a JRE exists for 21 and 25 on all five platforms the cloud supports.
- **File names** from an API must be plain base names: no `/`, `\`, `:`, `..`, control characters, `* ? " < > |`, trailing dot or space, and no Windows device name such as `nul` or `com1.jar` (`FileNames`). Anything else is a `MalformedResponseException`. `software.lock.yml` applies the same rule to `file-name`.

## Download flow

`ArtifactDownloader.download(resolved, lockedEntry)`:

1. If the SHA-256 is known (from the source or from the lock entry) and `cache/artifacts/<sha256>` exists, the file is hashed again. If it still matches, nothing is downloaded. A file that no longer matches is deleted.
2. Otherwise the file is streamed into `cache/artifacts/download-*.part`. SHA-256 and the source's checksum are computed in the same pass.
3. If a checksum does not match, the `.part` file is deleted and a `ChecksumMismatchException` (or `TofuMismatchException`) is thrown.
4. Only then is the file moved atomically to `cache/artifacts/<sha256>`.

Two more checks:

- If the lockfile has an entry for the same coordinate and the source now publishes a different checksum for it, the download fails. A published build must not change.
- `download(lockedEntry)` replays a lock entry without asking the API: same URL, same checksum.

`ArtifactCache.copyTo(sha256, target)` places a cached file into a server directory through a temporary file and an atomic move. It computes the SHA-256 again while copying. If the cache file has changed since it was stored, it is deleted, the target stays as it was and a `ChecksumMismatchException` is thrown; the next download fetches the file again.

A download that is cut off (crash, killed process) can leave a `download-*.part` file in `cache/artifacts/`. Before each download, `ArtifactCache.removeStalePartFiles()` deletes every part file that no download in this JVM is writing. Only one cloud process uses a home at a time, so such a file is always orphaned. The cloud can also call the method at startup.

### Trust on first use

For a source without a checksum (only the Fabric launcher so far) the first download is accepted. Its SHA-256 goes into the lockfile with `trust: tofu`, and `hash` equals `sha256`. Every later download for the same coordinate must have the same SHA-256, otherwise `TofuMismatchException` is thrown and the file is deleted. That also holds once the source starts to publish a checksum: the file must then match both, and the entry is stored with `trust: source`. If the change is expected, remove the entry from `software.lock.yml`; the next download pins the new file.

## `software.lock.yml`

The lockfile lives in the cloud home (`<home>/software.lock.yml`). It is written through a temporary file and an atomic move.

```yaml
# Written by VectoraCloud. Pins every downloaded artifact to one file and its hash.
# Format: docs/cloud/download.md. Do not edit while the cloud is running.
schema-version: 1
artifacts:
  fabric-server:
    provider: fabric
    project: fabric-server
    version: "26.3"
    build: loader-0.20.1+installer-1.1.2
    url: https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar
    file-name: fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar
    hash-type: sha256
    hash: 70446947655b4f8f286aba896aa4c19a255e8ca8a730636ef2a6017ae4415d95
    sha256: 70446947655b4f8f286aba896aa4c19a255e8ca8a730636ef2a6017ae4415d95
    trust: tofu
    size: 181838
  paper:
    provider: papermc
    project: paper
    version: "26.3"
    build: "227"
    url: https://fill-data.papermc.io/v1/objects/416b7d10.../paper-26.3-227.jar
    file-name: paper-26.3-227.jar
    hash-type: sha256
    hash: 416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15
    sha256: 416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15
    trust: source
    size: 55257780
    previous:
      provider: papermc
      project: paper
      version: "26.3"
      build: "226"
      # ... same fields, without its own previous
```

| Key | Meaning |
|---|---|
| `schema-version` | Format version, currently `1`. Another value is refused. |
| `artifacts.<name>` | One entry per name: lower case, digits, `.` and `-`, at most 64 characters. Usually the catalog id. |
| `provider`, `project`, `version`, `build` | The coordinate. `build` is missing for sources without builds (Modrinth, Hangar, Mojang, MariaDB). |
| `url` | HTTPS URL the file was downloaded from. |
| `file-name` | File name at the source, a plain base name (see the notes on providers). |
| `hash-type`, `hash` | The checksum the file was verified with: the strongest one the source offers (`sha1`, `sha256` or `sha512`). For `tofu` the pinned SHA-256. |
| `sha256` | SHA-256 of the file, its name in `cache/artifacts/`. |
| `trust` | `source` (checksum from the source) or `tofu` (pinned on first use). |
| `size` | Size in bytes. |
| `previous` | The entry this one replaced, for a rollback. Only one level is kept. |

Rules:

- Reading is strict. Unknown keys, duplicate keys, missing fields, invalid hashes, `http://` URLs and unquoted versions are errors. An unquoted `26.10` would be read as the number 26.1, so versions and builds must be strings; the writer quotes them where YAML would read something else.
- `SoftwareLock.put(name, entry)` makes the current entry the `previous` of the new one if it is a different file. Writing the same file again keeps the existing `previous`.
- `SoftwareLock.rollback(name)` swaps the entry and its `previous`, so a second rollback returns to the start.

## Catalog

`src/main/resources/net/vectora/cloud/download/catalog/catalog.yml` lists the artifacts the cloud needs. It is data; `ArtifactCatalog` only reads it. `CatalogEntry.toRequest(minecraftVersion)` builds the request for a group.

| Field | Meaning |
|---|---|
| `id` | Unique name, also the name in the lockfile |
| `kind` | `server`, `plugin`, `mod` or `database` |
| `type` | `standard` or `transitional` (third-party plugin until the Vectora replacement exists; requires `replaced-by`) |
| `provider`, `project`, `platform` | Where it comes from |
| `game-version` | `version` (the group's Minecraft version is the version to resolve, Paper), `filter` (only builds for that version, Modrinth and Fabric) or `none` (the proxy has no Minecraft version of its own) |
| `targets` | Server templates in `templates/` that use it |
| `replaced-by` | What replaces a transitional entry |
| `alternative` | A second source with a checksum, as `provider:project` |
| `version` | Optional. Pins the version to resolve. Only for `game-version: none`, where the group's Minecraft version says nothing about the version wanted. `velocity` is pinned to `4.3.1`, the Velocity API the bridge is compiled against (move both together); `pandaspigot` to `1.8.8`. |

Sources, checked on 2026-10-28 against the live APIs for Minecraft 26.3:

| Id | Type | Source | Checksum |
|---|---|---|---|
| `paper`, `velocity` | standard | Fill | sha256 |
| `fabric-server` | standard | Fabric-Meta | none (TOFU) |
| `pandaspigot` | standard | PandaSpigot API | sha256 |
| `mariadb` | standard | MariaDB REST | sha512 |
| `geyser-velocity`, `floodgate-velocity`, `floodgate-spigot` | standard | GeyserMC | sha256 |
| `floodgate-fabric` | standard | Modrinth `floodgate` (fabric) | sha512 |
| `viaversion`, `viabackwards`, `viarewind` | standard | Modrinth (velocity); Hangar as alternative | sha512 |
| `fabric-api`, `fabricproxy-lite`, `lithium`, `ferritecore`, `servercore` | standard | Modrinth (fabric) | sha512 |
| `luckperms-velocity`, `luckperms-bukkit`, `luckperms-fabric` | transitional | Modrinth `luckperms` | sha512 |
| `tab` | transitional | Modrinth `tab-was-taken` (paper) | sha512 |
| `placeholderapi` | transitional | Modrinth (paper) | sha512 |
| `worldedit` | transitional | Modrinth (paper); Hangar `WorldEdit` | sha512 |
| `worldguard` | transitional | Modrinth (paper). Not on Hangar. | sha512 |
| `chunky` | transitional | Modrinth (fabric) | sha512 |
| `spark` | transitional | Modrinth (fabric). Not on Hangar. | sha512 |
| `proxyportal` | transitional | Modrinth (fabric) | sha512 |

Every catalog entry has a source with a checksum except the Fabric launcher. A Hangar alternative is listed where Hangar has a release for 26.3 with a checksum. There is none for TAB and PlaceholderAPI (both on Hangar, but without a 26.3 release on 2026-10-28), LuckPerms, WorldGuard and spark (not on Hangar), and the Fabric mods, which Hangar does not host. The catalog mirrors what the servers use today ([Getting started](../wiki/Getting-Started.md)): Chunky, spark and ProxyPortal run as Fabric mods on the Mod-Server.

## What the node installs

`cloud/node` uses the catalog through `software.SoftwareInstaller` (its `ServerSoftwareProvider`) and `software.LockedDownloads`:

- **Server jar.** `paper`, `velocity`, `fabric-server` or `pandaspigot`, by the group's `software`, copied into the service folder as `paper.jar`, `velocity.jar`, `fabric-server.jar` or `pandaspigot.jar`.
- **Plugins and mods.** An empty `catalog` in the group file means the catalog's defaults: every `plugin` or `mod` entry whose `targets` name the group's template and whose platform fits the software (mods only on Fabric, Velocity plugins only on Velocity). A non-empty `catalog` is exactly that list; an unknown id or one for another platform fails the start and names the id. Plugins go to `plugins/`, mods to `mods/`. The legacy proxy gets no defaults yet (Bedrock and ViaVersion on the legacy side come with PR 24).
- **Lock names.** An entry with `game-version: none` is locked under its `id`; one that depends on the Minecraft version under `<id>-<minecraft version>` (`paper-26.3`, `floodgate-fabric-26.3`), so groups on different versions do not share a pin.
- **Pinning.** The first resolve of a lock name asks the provider for the newest stable version (or the pinned `version`), downloads it and writes the coordinate and checksum to `software.lock.yml`. Every later start replays that entry: from `cache/artifacts/<sha256>` without the network, or by downloading exactly that file again. A newer upstream build is only taken after the entry is removed from the lockfile. A lock entry that no longer fits (another provider, another pinned version, another Minecraft version) is resolved again.
- **What the checksum proves.** Fill, Modrinth, Hangar, GeyserMC and the PandaSpigot API publish the checksum in the same API answer that names the download URL. It catches a damaged or swapped download and a mirror that serves something else, but not a compromised source, project account or API: whoever controls the answer controls both. The first resolve of an unpinned entry is therefore trust in the source at that moment, like trust on first use; after that the lockfile pins exactly that file. Catalog entries name Modrinth and Hangar projects by slug, not by a fixed project id. `software.lock.yml` is trusted like the rest of the home folder: whoever can write it can pin another file from an allowed host, so it must only be writable by the cloud's user.
- **Java minimum.** The highest of Fill's `java.version.minimum` and Mojang's `javaVersion` for that Minecraft version is noted per group in `cache/java-minimums.properties`, so `ManagedJavaRuntimes` knows it on a later offline start.
- **Bridge.** `BridgePlugins` copies `VectoraCloudBridge{Velocity,Paper,Legacy}-<version>.jar` from the node's own resources (listed with their SHA-256 in `bridges.index`, built by `:cloud:node:bridgeResources`) into `plugins/`, checked against that hash, and removes older versions of the same bridge. Fabric has no bridge yet.
- **Bookkeeping.** `plugins/.vectora-managed` (`mods/.vectora-managed`) lists the files the node put there. On the next start, a listed file that is no longer wanted is deleted; files the operator added are not touched.

## Safe extraction

`SafeExtractor` unpacks zip (MariaDB and JREs on Windows) and tar.gz (MariaDB and JREs on Linux and macOS) into a directory that does not exist or is empty.

- Entry names must be relative, use `/` and contain no `..`, `\`, `:` or NUL. Anything else is refused (zip slip).
- Symbolic links (TAR only) are created after all files, so no file is ever written through a link. A link target must be relative and stay inside the directory: first checked on the path, then on the real path right after the link is created, and once more when all links exist. That catches chains such as `x -> .` plus `a -> x/..`. A target whose missing part contains `..` (`nonexist/../x`) is refused, because the kernel would resolve it only once that part exists.
- Before a parent directory is created, the deepest part of its path that already exists is resolved for real and must lie inside the target. So a link can never be used to create directories outside, for example with `s -> .`, `l -> s/..` and `l/newdir/x -> y`.
- Hard links must point to a file extracted before them and are written as copies.
- Zip entries are always written as plain files. The JDK's zip API does not expose the Unix mode, so a zip cannot create links here.
- `ExtractionLimits` caps entries (default 100,000), bytes per file (2 GiB) and bytes in total (8 GiB). The bytes are counted while writing, never taken from the archive's size fields (zip bombs).
- If extraction fails, everything written so far is deleted.
- TAR permission bits are applied on POSIX file systems, without setuid, setgid and sticky.

`TarReader` is a small reader on JDK classes only (`GZIPInputStream` for the compression). It reads POSIX ustar, pax extended headers (`path`, `linkpath`, `size`) and the GNU long-name records `L` and `K`, which GNU tar writes for MariaDB's Linux archive (checked on the first 5 MB of `mariadb-11.8.9-linux-systemd-x86_64.tar.gz`). It verifies every header checksum and refuses unknown magic, devices, FIFOs, sparse files, base-256 sizes, metadata records over 64 KiB and streams that end before the end-of-archive block.

## Tests

- Unit tests run without a network. `FakeTransport` replaces the HTTP layer; the provider tests use stored API answers under `src/test/resources/net/vectora/cloud/download/provider/`, saved on 2026-10-28 and trimmed.
- Covered: wrong hash aborts and deletes, redirect to a foreign host or to HTTP, zip slip and tar slip, zip bomb by size and by entry count, links out of the target (also as chains), TOFU mismatch and a TOFU pin that stays binding once the source publishes a checksum, rate limits with `Retry-After`, lockfile round trip and rollback, catalog content.
- Fuzz tests (Jazzer, regression mode in every test run, fuzzing at night): `TarReaderFuzzTest`, `ProviderJsonFuzzTest` (every JSON mapper, Adoptium included), `SoftwareLockFileFuzzTest` and `JreFilesFuzzTest` (`release` file, `-XshowSettings` output, install marker). Seeds include TAR files written by Python's `tarfile` in ustar, GNU and pax format.
- Seven tests need symlinks or POSIX permissions and are skipped on Windows; CI runs them on Linux. The CI uploads the JUnit XML of every test run as an artifact (`junit-xml-ubuntu-latest`, `junit-xml-windows-latest`, `junit-xml-java21`), so it is visible there which tests ran and which were skipped.

### Live smoke test

`LiveSmokeTest` is tagged `network` and excluded from the normal build. Run it on purpose:

```sh
./gradlew :cloud:download:networkTest
```

It resolves Paper 26.3, Velocity, the Fabric launcher for 26.3, PandaSpigot, Geyser and Floodgate for Velocity, ViaVersion from Modrinth and from Hangar, Lithium, MariaDB for Windows and Linux, Mojang's Java version for 26.3 and the Temurin JREs 21 and 25 for Windows x64, Linux x64 and aarch64 and macOS x64 and aarch64. For each artifact it opens the download URL through the real client, so the redirect chain is checked against the allowlist too. Then it downloads Chunky (sha512) and the Fabric launcher (TOFU), checks both, downloads them a second time against the lockfile and writes a lockfile. Last, `JreManager` downloads the JRE 21 for the machine it runs on, unpacks it into `jre/` and runs `java -version` from it. The summary goes to `build/network-smoke.txt`.

The workflow `.github/workflows/network-smoke.yml` runs it every week, on demand and on pull requests that change the workflow or the test. It uploads the summary and the JUnit XML as the artifact `network-smoke`.

## JRE management

The `jre` package gives each server group a Java runtime. `cloud/node` calls one method and gets the path of `java` back:

```java
JavaRequirement requirement = JavaRequirement.forGroup(group.software(), minimum, group.java().major());
JavaRuntime java = jreManager.provide(requirement);   // java.executable() goes into the ProcessBuilder
```

### Which Java a group gets

`JavaRequirement.choose()`:

1. `java: <major>` in the group file wins, unless it is below the minimum. Then the group fails with a `JreException` that names both versions, because the server would not start anyway.
2. Legacy groups (`software: pandaspigot`, the 1.8.8 groups) get Java 21. PandaSpigot runs on 25 too, but Netty prints a `sun.misc.Unsafe` warning there ([Legacy 1.8.8 groups](legacy-1.8.8.md)).
3. Every other group gets Java 25.
4. If the minimum is higher than that, the minimum wins.

The minimum is Mojang's `javaVersion.majorVersion` (`MojangProvider.javaMajorVersion`) or Fill's `java.version.minimum` (`ResolvedArtifact.javaMinimum()`); with both, `JavaRequirement.minimumOf` takes the higher one. The table per software is in [Java compatibility](../java-compatibility.md#jres-for-the-servers).

### Where the runtime comes from

`JreManager.provide(feature)` tries, in this order:

1. **System Java** (`SystemJavaFinder`, can be switched off by passing `null`): `JAVA_HOME`, then each `java` on the `PATH`. The `release` file in the Java home tells the version without starting a JVM. Only a `java` without one (a launcher stub such as Oracle's `javapath`) is run with `java -XshowSettings:properties -version`, with a 15 s timeout. A runtime counts if its feature version is exactly the wanted one and its `OS_ARCH`/`os.arch` fits the machine. Java 26 does not stand in for 25, and 25 not for 21. Relative `PATH` entries are skipped, so a `java` in the working directory is never started.
2. **The managed runtime from before**, if its marker names the archive that `software.lock.yml` pins under `jre-temurin-<feature>-<os>-<arch>` (plus `-musl` on musl Linux). No network access.
3. **A download.** With a lock entry the pinned archive is downloaded again, without asking the API. Without one, the newest Temurin JRE of that feature version is resolved, downloaded, installed and then pinned with `trust: source`.

`JreManager.update(feature)` resolves the newest release. If it differs from the pinned one, it is installed and pinned; `previous` in the lockfile keeps the old entry for a rollback.

### Layout and installation

Runtimes live in `<home>/jre/<vendor>-<feature>-<os>-<arch>/`, for example `jre/temurin-21-linux-x64/` or `jre/temurin-25-windows-x64/`. `JreInstaller.install`:

1. unpacks the verified archive with `SafeExtractor` into `jre/.staging-<name>-*`, so the zip-slip, link and size rules above apply. TAR permission bits are kept, so `bin/java` stays executable on Linux and macOS;
2. finds the Java home: the archive root, its single top-level directory, or `Contents/Home` below it (macOS);
3. checks that `release` names the expected feature version, that `bin/java` (`bin\java.exe` on Windows) is a regular file and, for a Linux or macOS runtime on a POSIX file system, executable (a Windows zip has no permission bits, and `java.exe` needs none);
4. writes `.vectora-jre.properties` (SHA-256 of the archive, relative Java home, version);
5. moves the staging directory into place. An older install is moved to `jre/.old-<name>-*` first and deleted. If a running server still holds files there (Windows), it is removed on the next install, together with any staging directory an interrupted install left.

If a step fails, the staging directory is deleted and an existing install stays as it was. A marker whose Java home points outside its directory is ignored, and the runtime is installed again.
