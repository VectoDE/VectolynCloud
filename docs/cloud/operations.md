# Running VectoraCloud

Deutsch: [operations.de.md](operations.de.md)

This page covers how to run `VectoraCloud.jar` on Windows, Linux (VPS or dedicated server, with systemd), macOS, Docker and Pterodactyl, and how to check that a download really comes from this repository. We do not host anything: every network built with Vectora runs on its operator's own machine.

The files are in [`VectoraCloud/dist/`](../../VectoraCloud/dist):

| File | Purpose |
|---|---|
| `start.sh` | start script for Linux and macOS (bash 3.2 or later) |
| `start.bat`, `start.ps1` | start scripts for Windows (Command Prompt, Windows PowerShell 5.1 or PowerShell 7) |
| `systemd/vectora-cloud.service` | systemd unit with a dedicated user and hardening |
| `docker/Dockerfile`, `docker/compose.yml`, `docker/setup.example.yml` | container image and a compose file with MariaDB |
| `pterodactyl/egg-vectora-cloud.json` | Pterodactyl egg |
| `package.sh` | builds the release archives (used by the release workflow) |

## What a release contains

The release workflow ([`release.yml`](../../.github/workflows/release.yml)) runs on a `v*` tag and attaches:

| File | Content |
|---|---|
| `VectoraCloud.jar` | the cloud, for `java -jar`, Docker and Pterodactyl |
| `VectoraCloud-<version>-windows.zip` | `VectoraCloud.jar`, `start.bat`, `start.ps1` |
| `VectoraCloud-<version>-linux-macos.tar.gz` | `VectoraCloud.jar`, `start.sh` (executable) |
| `SHA256SUMS` | SHA-256 of the three files above |
| `<file>.sigstore.json` | cosign signature bundle for each file and for `SHA256SUMS` |
| `vectora.intoto.jsonl` | SLSA level 3 provenance for every file in `SHA256SUMS` |

The start scripts come in one archive per operating system rather than as loose files: a downloaded `start.sh` loses its executable bit, while the tar keeps it; the zip keeps the CRLF line ends the Windows scripts need; and each archive is a single download with the matching jar. The jar is attached on its own as well. The archives are reproducible (fixed file order, owner and time stamps), so building the same tag twice gives the same SHA-256.

The container image goes to `ghcr.io/vectode/vectora-cloud`, tagged with the version (`0.2.0`, without `v`). `latest` is set only for stable releases; `v0.*` versions and tags with a suffix (`v1.0.0-rc.1`) are pre-releases and do not move `latest`. The image contains the very `VectoraCloud.jar` of the release: the workflow passes the signed jar into the image build instead of compiling it again, and checks its SHA-256 against `SHA256SUMS` first.

## Requirements

- **Java 21 or later** for the cloud itself. The servers it starts get the Java version their Minecraft version needs; if no installed Java fits, the cloud downloads a Temurin JRE into `<home>/jre/` (see [node.md](node.md#java-runtimes)).
- A normal user account. The cloud refuses to start as root or administrator (see [below](#never-as-root-or-administrator)).
- Memory: the cloud's own heap (`VECTORA_XMX`, default 512M) plus the heap of every server it runs.
- **Internet access on the first start of a group.** The cloud then downloads the server software (Paper, Velocity, Fabric, PandaSpigot) and the plugins and mods of the catalog: Geyser and Floodgate for Bedrock, ViaVersion, ViaBackwards and ViaRewind so Java clients from 1.8 to 26.3 can join, LuckPerms, the Fabric performance mods. Every file is checked against its checksum and pinned in `software.lock.yml`; later starts take it from `cache/artifacts/` without the network ([download.md](download.md#what-the-node-installs)).

## First start: what lands in each server

Before each start the node fills the server's folder: the server jar, the plugins (or mods on Fabric) its group needs, and the VectoraCloud bridge of its platform. It notes what it put there in `plugins/.vectora-managed` (`mods/` on Fabric) and removes those files again once the group no longer wants them; plugins you copy in yourself stay. A PandaSpigot 1.8.8 server does not start without its bridge, because only the bridge checks the legacy proxy's token. Details: [node.md](node.md#server-software).

## Start scripts

```sh
./start.sh [arguments]                 # Linux, macOS
start.bat [arguments]                  # Windows, Command Prompt
powershell -ExecutionPolicy Bypass -File .\start.ps1 [arguments]   # Windows PowerShell
```

Every argument is passed to `VectoraCloud.jar` unchanged: `--non-interactive`, `--setup-file <file>`, `--lang de|en`, `--allow-root`, `--home <path>`, `--help`, `--version` ([launcher.md](launcher.md#arguments)).

| Variable | Default | Meaning |
|---|---|---|
| `VECTORA_HOME` | folder of the script | cloud home; the script changes into it, so relative paths in arguments (`--setup-file setup.yml`) are relative to the home |
| `VECTORA_JAR` | `VectoraCloud.jar` next to the script | path of the jar |
| `VECTORA_XMX` | `512M` | maximum heap of the cloud process, digits plus `K`, `M` or `G` |
| `JAVA_HOME` | (unset) | Java to use. If it has no `bin/java`, the script says so and uses `java` from the `PATH` |
| `JDK_JAVA_OPTIONS` | (unset) | further JVM options; read by the `java` launcher itself |

How Java is found: `JAVA_HOME` first, then the `PATH`. Without any Java the script prints where to get one (<https://adoptium.net/temurin/releases/>) and exits with 3. The version is checked by `VectoraCloud.jar` itself: its first class is compiled for Java 8, so Java 8 to 20 print "VectoraCloud needs Java 21 or newer" with the version and path found, instead of a class file error. For the same reason the scripts pass no JVM flag that old Java versions reject; native access for the console is declared in the jar's manifest (`Enable-Native-Access: ALL-UNNAMED`).

Safety of the scripts:

- `start.sh` runs with `set -euo pipefail`, quotes every expansion and starts Java with `exec`, so Java receives the signals directly (systemd, `docker stop`). It passes ShellCheck and works with the bash 3.2 that macOS ships.
- `start.bat` reads the environment with delayed expansion and strips quotes, so `&`, `|`, `<`, `>` or parentheses in a variable are never run as a command; the arguments are passed on with delayed expansion off, so a `!` in them stays as typed.
- `start.ps1` has no `Invoke-Expression` and no command string; arguments go to Java as an array. It passes PSScriptAnalyzer.
- None of them uses `sudo` or asks for elevation.

The CI workflow [`operations.yml`](../../.github/workflows/operations.yml) runs every script on Linux, macOS and Windows: version and help, a Java 17 that is too old, no Java at all, a `JAVA_HOME` without Java, a missing jar, a broken `VECTORA_XMX`, values with `&` and quotes in the environment, a folder name with spaces, parentheses, `&` and `!`, a root start with and without `--allow-root`, and the scripts from the release archives.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | stopped normally, or `--help` / `--version` |
| 1 | the node threw while starting |
| 2 | started as root or administrator without `--allow-root` |
| 3 | no Java found, or Java older than 21 |
| 4 | an embedded library is missing or has the wrong SHA-256 |
| 5 | no node entry point in this build |
| 64 | invalid argument, or `VECTORA_XMX` is not a size |
| 66 | `VectoraCloud.jar` or the home folder not found (start scripts) |
| 143 | ended by SIGTERM (systemd, `docker stop`) after the clean shutdown |

### Never as root or administrator

The cloud downloads and runs server software. With root or administrator rights a bug or a tampered download would hit the whole machine, so the launcher refuses such a start with exit code 2 and explains what to do instead ([launcher.md](launcher.md#root-and-administrator-check)). `--allow-root` or `VECTORA_ALLOW_ROOT=true` allow it anyway and print a warning on every start; use that only where no other way exists.

## First start: the setup

Until `cloud.yml` says `setup-complete: true`, the cloud starts no server. With a terminal it asks its questions; without one (systemd, Docker without `-it`, Pterodactyl) it needs a setup file:

```sh
./start.sh --non-interactive --setup-file setup.yml
```

Format and rules: [setup.md](setup.md). The file holds no secret; a database password comes from a file (`database.password-file`) or from `secrets/vectora.env`. Once the setup is complete, the file is ignored.

## Windows

1. Install Java 21 or later (Temurin from <https://adoptium.net/temurin/releases/>, or any other build).
2. Unpack `VectoraCloud-<version>-windows.zip` into a folder of its own, for example `C:\Vectora`. That folder becomes the home.
3. Open a normal Command Prompt or PowerShell (not "Run as administrator") and run `start.bat`.

PowerShell blocks scripts downloaded from the internet with the default execution policy. Either run `Unblock-File .\start.ps1` once, or start it with `powershell -ExecutionPolicy Bypass -File .\start.ps1`. `start.bat` needs neither.

Ctrl+C at the prompt asks first and stops everything on a second Ctrl+C (see [console.md](console.md)); afterwards the Command Prompt may ask "Terminate batch job (Y/N)?", which no longer matters.

To run it permanently, use a dedicated local account without administrator rights, for example with the Task Scheduler ("Run whether user is logged on or not", trigger "At startup"). The repository ships no Windows service wrapper.

## Linux and VPS with systemd

```sh
# as root, once
useradd --system --user-group --home-dir /opt/vectora --create-home --shell /usr/sbin/nologin vectora
tar -xzf VectoraCloud-<version>-linux-macos.tar.gz
install -o vectora -g vectora -m 0644 VectoraCloud-<version>-linux-macos/VectoraCloud.jar /opt/vectora/
install -o vectora -g vectora -m 0755 VectoraCloud-<version>-linux-macos/start.sh /opt/vectora/
install -o root -g root -m 0644 vectora-cloud.service /etc/systemd/system/
systemctl daemon-reload

# first start with questions, as the vectora user; end with "shutdown"
sudo -u vectora /opt/vectora/start.sh

systemctl enable --now vectora-cloud
journalctl -u vectora-cloud -f
```

Java: any Java 21+ on the `PATH`, for example the distribution's `openjdk-21-jre-headless` or Temurin from the Adoptium repository. For a Java outside the `PATH`, set `JAVA_HOME` in a drop-in (`systemctl edit vectora-cloud`):

```ini
[Service]
Environment=JAVA_HOME=/usr/lib/jvm/temurin-25-jre-amd64
Environment=VECTORA_XMX=1G
```

Instead of the questions, a setup file works too: put it into `/opt/vectora/setup.yml` and use the second `ExecStart` line from the unit in a drop-in.

What the unit does and why:

- **Dedicated user** `vectora`, never root. The cloud would refuse root anyway.
- **Stop:** `KillMode=mixed` sends SIGTERM to the cloud only. The cloud then stops every server with its own stop command and waits for it, stops the managed database last and exits. With the default `control-group`, every server would get SIGTERM at the same moment. `TimeoutStopSec=5min` leaves room for that; raise it if you run many servers with long saves. Exit code 143 after SIGTERM counts as success.
- **Restart** on failure after 15 s, but not for exit codes a restart cannot fix (2, 3, 4, 5, 64, 66), and at most five times in ten minutes.
- **Hardening:** `NoNewPrivileges`, `ProtectSystem=strict` with `ReadWritePaths=/opt/vectora` (the cloud and its servers may write only to their home), `ProtectHome`, `PrivateTmp`, `PrivateDevices`, kernel and control group protection, an empty capability set, `SystemCallFilter=@system-service`, and `UMask=0027`. `MemoryDenyWriteExecute` is not set because the JVM's JIT compiler needs writable and executable memory. `systemd-analyze security vectora-cloud` shows the resulting exposure score.
- **MariaDB socket and `PrivateTmp`:** the managed MariaDB puts its socket into the temp directory. `PrivateTmp` gives the whole unit one private `/tmp`, shared by the cloud, its servers and MariaDB, so the socket works between them; the servers connect over TCP on `127.0.0.1` anyway. An external MariaDB on the same host is reached over TCP as well, so the private `/tmp` does not hide its socket from anything that needs it.
- **Limits:** `LimitNOFILE=65536` and `TasksMax=8192`; every thread of every server JVM counts as a task.

Under systemd the cloud has no console: stdin is empty and the cloud keeps running. Commands without a console come with the API ([architecture.md](architecture.md)). Until then, run it in `tmux` or `screen` as the `vectora` user when you need the console.

The CI installs this unit on a real systemd exactly as above, starts it, waits for the cloud channel, checks the user and stops it again with a clean result.

## macOS

1. Install Java 21 or later (Temurin `.pkg` from Adoptium, or `brew install --cask temurin`).
2. Unpack the tar: `tar -xzf VectoraCloud-<version>-linux-macos.tar.gz`, then `cd` into the folder and run `./start.sh`.

MariaDB publishes no server build for macOS, so the managed database mode falls back to the embedded H2 database and says so in the log ([database.md](database.md)). For a shared database, use `external` (for example MariaDB from Homebrew) or Docker. The repository ships no launchd file.

## Docker

```sh
docker run -it --init -v vectora:/data -p 25565:25565 -p 19132:19132/udp \
  ghcr.io/vectode/vectora-cloud:<version>
```

Without questions:

```sh
docker run -d --init --name vectora -v vectora:/data -v ./setup.yml:/data/setup.yml:ro \
  -p 25565:25565 -p 19132:19132/udp --stop-timeout 300 \
  ghcr.io/vectode/vectora-cloud:<version> --non-interactive --setup-file /data/setup.yml
```

The image:

- is built in stages: a JDK 25 stage builds `VectoraCloud.jar` from source, the runtime stage is `eclipse-temurin:25-jre-noble`. Both base images are pinned by digest and updated by Dependabot;
- runs as user `vectora` with UID and GID 1000. `/data` is the home and a volume; the jar and the start script in `/opt/vectora` belong to root and are read-only for the cloud. A bind mount instead of a volume must belong to UID 1000 (`chown -R 1000:1000 ./data`);
- uses `start.sh` as its entry point; arguments after the image name go to the cloud. `VECTORA_XMX` sets the heap of the cloud;
- stops with SIGTERM. Give it time with `--stop-timeout 300` (or `stop_grace_period` in compose), otherwise Docker kills the servers after 10 s;
- has a health check without curl or netcat: bash opens a TCP connection to the cloud channel on `127.0.0.1:7010` (`VECTORA_HEALTHCHECK_PORT` if you moved it in `nodes.yml`) and closes it before the channel's handshake timeout, so no log line is written;
- needs no Docker socket, no capability and no privileged mode. The `docker` database mode is for a cloud on a host with Docker; inside the container use `managed`, `external` (as in compose below) or `h2`;
- exists for `linux/amd64` and `linux/arm64`. On arm64 the managed database falls back to H2.

`--init` (or `init: true` in compose) puts a small init process in front of Java that reaps processes the servers leave behind.

Build it yourself from a checkout (the build context is the repository root):

```sh
docker build -f VectoraCloud/dist/docker/Dockerfile -t vectora-cloud .
```

### Docker Compose with MariaDB

[`compose.yml`](../../VectoraCloud/dist/docker/compose.yml) starts the cloud and MariaDB 11.8 as an external database:

```sh
cd VectoraCloud/dist/docker
cp setup.example.yml setup.yml          # set eula: true if you accept the Minecraft EULA
mkdir -p secrets
openssl rand -base64 32 | tr -d '\n' > secrets/db_password
openssl rand -base64 32 | tr -d '\n' > secrets/db_root_password
chmod 0644 secrets/db_password          # the cloud runs as UID 1000 and must be able to read it
docker compose up -d
docker compose logs -f cloud
```

- Passwords are Docker secrets (files under `/run/secrets/`), not environment variables, because those show up in `docker inspect` and in the environment of every server the cloud starts. MariaDB reads them through `MARIADB_PASSWORD_FILE`, the cloud through `database.password-file` in `setup.yml`.
- MariaDB publishes no port; only the cloud reaches it over the compose network (host `database`).
- Ports: Java 25565/tcp and Bedrock 19132/udp are published; the legacy proxy (25564/tcp) is commented out until you turn it on.
- `stop_grace_period: 5m`, `pids_limit: 8192`, `no-new-privileges`, and all capabilities dropped for the cloud.
- `secrets/` and `setup.yml` in that folder are in `.gitignore`.

## Pterodactyl

1. In the admin area, under Nests, import [`egg-vectora-cloud.json`](../../VectoraCloud/dist/pterodactyl/egg-vectora-cloud.json) (PTDL_v2).
2. Create a server with the egg. Give it **one allocation per port**: the primary allocation is the Java port, plus one for Bedrock (UDP) and, if you turn it on, one for the legacy proxy. Enter those ports in the variables.
3. Set "Accept the Minecraft EULA" to `true` if you accept it, then start the server.

The installer (in `ghcr.io/pterodactyl/installers:alpine`) downloads `VectoraCloud.jar` of the chosen release (`latest` is the newest release including pre-releases) and checks it against the release's `SHA256SUMS`; on a mismatch it stops and keeps nothing. The server runs in the Pterodactyl Java image (`yolks:java_25` or `java_21`) as the panel's unprivileged user, so the root check passes.

How the egg works:

| Part | Value |
|---|---|
| Startup | `java -Xms128M -Xmx{{CLOUD_HEAP}} -jar VectoraCloud.jar --non-interactive --setup-file setup.yml --lang {{CLOUD_LANGUAGE}}` |
| Setup file | Wings writes `setup.yml` from the variables before every start (`config.files`, parser `yaml`): `schema-version`, `eula`, `language`, `network-name`, `network.bind`, `network.java-port` (the primary allocation), `network.bedrock-port`, `network.legacy-port`, `servers.legacy-proxy`, `database.mode`. It counts only for the first setup. |
| Started when | the log shows `Cloud channel listens on` |
| Stop | the console command `shutdown`: every server gets its stop command, then the cloud ends |
| Variables | version, cloud heap, EULA, language, network name, Bedrock port, legacy proxy on/off, legacy proxy port, database mode |

- **Database:** `managed` (the cloud runs its own MariaDB inside the server; works on x86_64 because the Pterodactyl images are glibc-based), `external` (a database from the panel's database tab) or `h2`. The `docker` mode is not offered: the server is a container without Docker, and the setup refuses it there too. For `external`, open `setup.yml` in the file manager and add under `database:` the keys `host`, `port`, `name`, `user` and `password-file: db-password.txt`, and put the password into `db-password.txt`; Wings only replaces the keys it manages and keeps the others. Alternatively put `VECTORA_DB_PASSWORD=...` into `secrets/vectora.env` ([setup.md](setup.md#pterodactyl)).
- **Memory:** the server's memory limit covers the cloud and every Minecraft server it starts. The setup warns when the heaps add up to more than `SERVER_MEMORY`. Keep the cloud heap small (512M) and give the servers the rest.
- **PID limit:** Wings limits every server container to **512 processes and threads** by default (`docker.container_pid_limit` in `/etc/pterodactyl/config.yml`). Each Minecraft server JVM uses roughly 50 to 150 threads, the proxy and MariaDB more, so several servers in one Pterodactyl server run into this limit and then fail with "unable to create native thread" or `OutOfMemoryError`. Raise it (for example to 4096) in the Wings configuration and restart Wings; that needs access to the node, not only to the panel.
- **Other limits:** one node only (no cluster), and every port needs an allocation. A network name of digits only, or `true`/`false`, would be turned into a number or boolean by Wings; the variable's rule requires at least one letter.

## Verifying a release

Every file of a release is signed with cosign keyless (Sigstore, the GitHub Actions identity of `release.yml`). The three files in `SHA256SUMS` also have a GitHub build provenance attestation and are listed in the SLSA provenance; `SHA256SUMS` itself is covered by its signature. Replace `v0.2.0` with the tag you downloaded.

```sh
# 1. Checksums (only integrity: SHA256SUMS comes from the same place)
sha256sum --check --ignore-missing SHA256SUMS

# 2. cosign: signed by release.yml of this repository at this tag
cosign verify-blob VectoraCloud.jar \
  --bundle VectoraCloud.jar.sigstore.json \
  --certificate-identity "https://github.com/VectoDE/Vectora-Network/.github/workflows/release.yml@refs/tags/v0.2.0" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# 3. GitHub attestation (build provenance)
gh attestation verify VectoraCloud.jar --repo VectoDE/Vectora-Network

# 4. SLSA provenance
slsa-verifier verify-artifact VectoraCloud.jar \
  --provenance-path vectora.intoto.jsonl \
  --source-uri github.com/VectoDE/Vectora-Network --source-tag v0.2.0
```

The container image, always by digest or tag of a release:

```sh
cosign verify ghcr.io/vectode/vectora-cloud:0.2.0 \
  --certificate-identity "https://github.com/VectoDE/Vectora-Network/.github/workflows/release.yml@refs/tags/v0.2.0" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

gh attestation verify oci://ghcr.io/vectode/vectora-cloud:0.2.0 --repo VectoDE/Vectora-Network

# SBOM and BuildKit provenance stored with the image
docker buildx imagetools inspect ghcr.io/vectode/vectora-cloud:0.2.0 --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/vectode/vectora-cloud:0.2.0 --format '{{ json .Provenance }}'
```

To check that the image carries the released jar: `docker run --rm --entrypoint sha256sum ghcr.io/vectode/vectora-cloud:0.2.0 /opt/vectora/VectoraCloud.jar` must print the line from `SHA256SUMS`.

## What is tested where

| Part | Tested in CI ([operations.yml](../../.github/workflows/operations.yml)) | Only a real release or setup shows |
|---|---|---|
| Start scripts | Linux, macOS, Windows (PowerShell 5.1), all cases above | |
| Release archives | built with `package.sh`, built twice and compared, unpacked and started | the upload to the release |
| systemd unit | `systemd-analyze verify`, real start and stop on Ubuntu | long-running servers and the managed MariaDB under the hardening |
| Image | from source and through the release path, amd64 and arm64, start as UID 1000, root refused, health check, clean `docker stop` | push to GHCR, cosign signature, attestations, the `latest` rule |
| compose.yml | `docker compose config` | a full start with MariaDB |
| Egg | structure, variables, rules against their defaults, the setup file Wings would write, ShellCheck of the installer | import into a panel, the installer's download, a run under Wings |
| Release workflow | actionlint | signing, provenance and publishing on a tag |
