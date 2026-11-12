# Feature status

[Deutsch](Feature-Status-DE)

What exists, what exists in part and what is only planned, measured against the project's target list. "Built" means the code is in the repository and has tests; it does not mean production ready, because there is no stable release yet. Planned items link to the issue that tracks them. The table is updated in the pull request that changes the status.

The target list is the project's own specification. A fuller matrix with every individual feature is produced in [#132](https://github.com/VectoDE/VectolynCloud/issues/132); this page then links to it.

Status as of the pre-release [v0.1.1-dev](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.1-dev) and the `main` branch after it.

## Start and installation

| Feature | Status | Notes |
|---|---|---|
| Start with `java -jar VectolynCloud.jar` | Built | [Getting Started](Getting-Started) |
| Setup assistant in the console, Enter takes defaults | Built | [setup](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.md) |
| Setup file for unattended installs | Built | `--setup-file` |
| Panel admin created with a one-time setup token | Planned | [#125](https://github.com/VectoDE/VectolynCloud/issues/125) |
| Newest stable Minecraft version as default, older by choice | Planned | [#125](https://github.com/VectoDE/VectolynCloud/issues/125) |
| Start scripts for Windows, Linux, macOS | Built | in every release |
| Docker image on GHCR (amd64, arm64), compose file | Built | tags `0.1.1-dev` and `dev`; SBOM planned in [#221](https://github.com/VectoDE/VectolynCloud/issues/221) |
| Pterodactyl egg | Built | not yet tested under a real Wings, see [#11](https://github.com/VectoDE/VectolynCloud/issues/11) |
| Pelican egg | Planned | [#216](https://github.com/VectoDE/VectolynCloud/issues/216) |
| systemd unit | Built | installation script planned in [#217](https://github.com/VectoDE/VectolynCloud/issues/217) |
| Signed releases with checksums and provenance | Built | cosign and GitHub attestation, see [Operations](Operations) |

## Cloud core

| Feature | Status | Notes |
|---|---|---|
| Console with help and tab completion | Built | acceptance open in [#126](https://github.com/VectoDE/VectolynCloud/issues/126) |
| Groups, tasks, static and dynamic services | Built | [Configuration](Configuration) |
| Choice of wanted servers, only those are created | Built | |
| Autoscaling by player count and group load | Built | CPU, RAM, TPS, schedules and a queue are planned in [#128](https://github.com/VectoDE/VectolynCloud/issues/128) |
| Several nodes (cluster) | Partly | mutual TLS, join tokens, version check, template mirror; placement, failover and moving services are planned in [#73](https://github.com/VectoDE/VectolynCloud/issues/73) and [#127](https://github.com/VectoDE/VectolynCloud/issues/127) |
| YAML configuration with strict validation, reload | Built | |
| JSON Schema for editors, import and export, change preview | Planned | [#129](https://github.com/VectoDE/VectolynCloud/issues/129) |
| Databases: managed MariaDB, Docker, external MariaDB/MySQL, H2 | Built | PostgreSQL planned in [#129](https://github.com/VectoDE/VectolynCloud/issues/129) |
| Server software download with checksums and a lock file | Built | update test run and rollback planned in [#77](https://github.com/VectoDE/VectolynCloud/issues/77) |
| Java runtime management | Built | Adoptium, per group |
| Own messaging channel and bridges (Velocity, Paper, PandaSpigot 1.8.8) | Built | |
| More server software (Folia, Spigot, BungeeCord, Forge, NeoForge, Minestom, Sponge) and bridges for Nukkit and WaterdogPE | Planned | [#134](https://github.com/VectoDE/VectolynCloud/issues/134), [#135](https://github.com/VectoDE/VectolynCloud/issues/135) |
| Managed legacy proxy for 1.8.8 | Partly | [#78](https://github.com/VectoDE/VectolynCloud/issues/78) |
| Updates: migrations, signed self-update, maintenance mode, rollback | Planned | [#74](https://github.com/VectoDE/VectolynCloud/issues/74) to [#77](https://github.com/VectoDE/VectolynCloud/issues/77), [#130](https://github.com/VectoDE/VectolynCloud/issues/130) |
| Rotating logs with a configurable retention | Partly | the last five logs of the cloud are kept; configurable retention planned in [#130](https://github.com/VectoDE/VectolynCloud/issues/130) |
| Module system with a stable API | Planned | [#162](https://github.com/VectoDE/VectolynCloud/issues/162) |

## Operations

| Feature | Status | Notes |
|---|---|---|
| Backups of worlds and database, schedule, retention, restore | Built | [backups](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/backup.md) |
| S3-compatible backup storage | Planned | [#205](https://github.com/VectoDE/VectolynCloud/issues/205) |
| Restore in the panel | Planned | [#206](https://github.com/VectoDE/VectolynCloud/issues/206) |
| Metrics per service and node, alerts, webhooks, Prometheus endpoint | Built | [monitoring](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/monitor.md) |
| Log search | Planned | [#212](https://github.com/VectoDE/VectolynCloud/issues/212) |

## API and panel

| Feature | Status | Notes |
|---|---|---|
| REST API and WebSocket events, tokens with scopes, OpenAPI file | Built | [API](API) |
| gRPC | Planned | [#154](https://github.com/VectoDE/VectolynCloud/issues/154) |
| GraphQL | Planned | [#155](https://github.com/VectoDE/VectolynCloud/issues/155) |
| Roles and API keys | Planned | [#156](https://github.com/VectoDE/VectolynCloud/issues/156) |
| Web panel with logins, file manager, editors | Planned | [#151](https://github.com/VectoDE/VectolynCloud/issues/151) |

## Network and content

| Feature | Status | Notes |
|---|---|---|
| Java 1.8 to 26.3 and Bedrock on one proxy (Velocity, Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind) | Built | join tests with real clients still open in [#5](https://github.com/VectoDE/VectolynCloud/issues/5), see [Crossplay](Crossplay) |
| No command blocks, rights audit for staff commands | Built | |
| Own replacements for PlaceholderAPI, LuckPerms, TAB | Planned | [#167](https://github.com/VectoDE/VectolynCloud/issues/167) |
| Lobby, Survival, Creative, End, mod server, minigames, social | Planned | [#176](https://github.com/VectoDE/VectolynCloud/issues/176); today only small lobby and portal plugins exist |
| Moderation and tickets | Planned | [#182](https://github.com/VectoDE/VectolynCloud/issues/182) |
| Discord bot | Planned | [#188](https://github.com/VectoDE/VectolynCloud/issues/188) |
| Website and store | Planned | [#194](https://github.com/VectoDE/VectolynCloud/issues/194) |
| Anti-cheat | Planned | [#199](https://github.com/VectoDE/VectolynCloud/issues/199) |
| Resource pack | Planned | [#203](https://github.com/VectoDE/VectolynCloud/issues/203) |
| Privacy functions (retention, consent, statistics) | Planned | [#231](https://github.com/VectoDE/VectolynCloud/issues/231) |
| Translations and accessibility for the panel and website | Planned | [#235](https://github.com/VectoDE/VectolynCloud/issues/235) |

## Quality and trust

Built and visible in the repository: CI on Linux and Windows, tests on Java 21 and 25, end-to-end tests with the real jar, fuzz tests, CodeQL, dependency review, OpenSSF Scorecard and the OpenSSF Best Practices badge (level *passing*), signed releases with provenance, SHA-256 verification of every dependency. What the Scorecard cannot reach with one maintainer, and why: [docs/openssf.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/openssf.md).
