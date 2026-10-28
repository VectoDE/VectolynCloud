# Downloads

`VectoraCloud/cloud-download` (`net.vectora.cloud.download`) fetches server software, plugins, mods and the MariaDB server, checks every file against a checksum and keeps it in a content-addressed cache. `software.lock.yml` records exactly which file each name stands for. JREs from Adoptium follow in a later pull request as one more provider.

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
| `hash` | `Checksum`, `HashAlgorithm`, `MultiDigest` |

## HTTP rules

- Only `https://` on port 443, without user info. Plain HTTP is refused before anything is sent, also as a redirect target.
- Every provider has an allowlist of exact host names. The first URL and every redirect target must be on it. At most 5 redirects.
- Every request has a size limit. A Content-Length above the limit is refused at once, and the body is counted while it is read. API responses are limited to 16 MiB. A download is limited to the size the source announces, otherwise to 2 GiB.
- Timeouts: 15 s to connect, 30 s until the response headers arrive, 15 min per attempt in total. A watchdog closes a stalled connection when that time is up.
- Up to 3 attempts with exponential backoff (1 s, 2 s, at most 10 s) after network errors and 5xx answers. 4xx answers are not retried, and neither are a refused host or an exceeded limit.
- User-Agent: `VectoraCloud/<version> (+https://github.com/VectoDE/Vectora-Network)`. PaperMC and Modrinth ask for a User-Agent that names the application and a contact.

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

Notes:

- **MariaDB** resolves only. Windows x64 gets the zip, Linux x86_64 with glibc the `linux-systemd` tar.gz. Every other platform gets an `ArtifactNotAvailableException` that names the platform and points to the Docker mode or an external server. The API lists `http://` URLs. They are rewritten to `https://` and get `?mirror=archive`, because the default redirect goes to a geo-selected third-party mirror (`mirror.wtnet.de` from Germany) that no allowlist can know in advance. With `mirror=archive` the redirect goes to `archive.mariadb.org`.
- **Fabric** publishes no checksum for the server launcher. See trust on first use below.
- **Adoptium** comes as another `ArtifactProvider`. `ArtifactRequest` already carries what it needs: `system` (OS, CPU, C library) and free `options` such as `image-type`.

## Download flow

`ArtifactDownloader.download(resolved, lockedEntry)`:

1. If the SHA-256 is known (from the source or from the lock entry) and `cache/artifacts/<sha256>` exists, the file is hashed again. If it still matches, nothing is downloaded. A file that no longer matches is deleted.
2. Otherwise the file is streamed into `cache/artifacts/download-*.part`. SHA-256 and the source's checksum are computed in the same pass.
3. If a checksum does not match, the `.part` file is deleted and a `ChecksumMismatchException` (or `TofuMismatchException`) is thrown.
4. Only then is the file moved atomically to `cache/artifacts/<sha256>`.

Two more checks:

- If the lockfile has an entry for the same coordinate and the source now publishes a different checksum for it, the download fails. A published build must not change.
- `download(lockedEntry)` replays a lock entry without asking the API: same URL, same checksum.

`ArtifactCache.copyTo(sha256, target)` places a cached file into a server directory through a temporary file and an atomic move.

### Trust on first use

For a source without a checksum (only the Fabric launcher so far) the first download is accepted. Its SHA-256 goes into the lockfile with `trust: tofu`, and `hash` equals `sha256`. Every later download for the same coordinate must have the same SHA-256, otherwise `TofuMismatchException` is thrown and the file is deleted. If the change is expected, remove the entry from `software.lock.yml`; the next download pins the new file.

## `software.lock.yml`

The lockfile lives in the cloud home (`Server-Network/software.lock.yml`). It is written through a temporary file and an atomic move.

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
| `file-name` | File name at the source. |
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
| `targets` | Server templates in `Server-Network/` that use it |
| `replaced-by` | What replaces a transitional entry |
| `alternative` | A second source with a checksum, as `provider:project` |

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

## Safe extraction

`SafeExtractor` unpacks zip (MariaDB on Windows, later JREs) and tar.gz (MariaDB on Linux, later JREs) into a directory that does not exist or is empty.

- Entry names must be relative, use `/` and contain no `..`, `\`, `:` or NUL. Anything else is refused (zip slip).
- Symbolic links (TAR only) are created after all files, so no file is ever written through a link. A link target must be relative and stay inside the directory: first checked on the path, then, once all links exist, on the real path. That catches chains such as `x -> .` plus `a -> x/..`.
- Hard links must point to a file extracted before them and are written as copies.
- Zip entries are always written as plain files. The JDK's zip API does not expose the Unix mode, so a zip cannot create links here.
- `ExtractionLimits` caps entries (default 100,000), bytes per file (2 GiB) and bytes in total (8 GiB). The bytes are counted while writing, never taken from the archive's size fields (zip bombs).
- If extraction fails, everything written so far is deleted.
- TAR permission bits are applied on POSIX file systems, without setuid, setgid and sticky.

`TarReader` is a small reader on JDK classes only (`GZIPInputStream` for the compression). It reads POSIX ustar, pax extended headers (`path`, `linkpath`, `size`) and the GNU long-name records `L` and `K`, which GNU tar writes for MariaDB's Linux archive (checked on the first 5 MB of `mariadb-11.8.9-linux-systemd-x86_64.tar.gz`). It verifies every header checksum and refuses unknown magic, devices, FIFOs, sparse files, base-256 sizes, metadata records over 64 KiB and streams that end before the end-of-archive block.

## Tests

- Unit tests run without a network. `FakeTransport` replaces the HTTP layer; the provider tests use stored API answers under `src/test/resources/net/vectora/cloud/download/provider/`, saved on 2026-10-28 and trimmed.
- Covered: wrong hash aborts and deletes, redirect to a foreign host or to HTTP, zip slip and tar slip, zip bomb by size and by entry count, links out of the target, TOFU mismatch, lockfile round trip and rollback, catalog content.
- Fuzz tests (Jazzer, regression mode in every test run, fuzzing at night): `TarReaderFuzzTest`, `ProviderJsonFuzzTest` (every JSON mapper) and `SoftwareLockFileFuzzTest`. Seeds include TAR files written by Python's `tarfile` in ustar, GNU and pax format.
- Three tests need symlinks or POSIX permissions and are skipped on Windows; CI runs them on Linux.

### Live smoke test

`LiveSmokeTest` is tagged `network` and excluded from the normal build. Run it on purpose:

```sh
./gradlew :VectoraCloud:cloud-download:networkTest
```

It resolves Paper 26.3, Velocity, the Fabric launcher for 26.3, PandaSpigot, Geyser and Floodgate for Velocity, ViaVersion from Modrinth and from Hangar, Lithium, MariaDB for Windows and Linux and Mojang's Java version for 26.3. For each artifact it opens the download URL through the real client, so the redirect chain is checked against the allowlist too. Then it downloads Chunky (sha512) and the Fabric launcher (TOFU), checks both, downloads them a second time against the lockfile and writes a lockfile. The summary goes to `build/network-smoke.txt`.
