# Architecture

[Deutsch](Architecture-DE)

> Most of this page describes the planned target. Parts that exist today are marked **today**.

## Layers

```
 Java 1.8 to 26.3         Bedrock
        │                    │
        ▼                    ▼
 ┌──────────────────────────────────────┐
 │ Proxy: Velocity                      │
 │ ViaVersion/ViaBackwards/ViaRewind    │
 │ Geyser + Floodgate                   │
 └──────────────────┬───────────────────┘
                    │ modern forwarding
   ┌────────┬───────┼─────────┬─────────┬───────────┐
   ▼        ▼       ▼         ▼         ▼           ▼
 Lobby  Survival Creative  Mod (Fabric) End    Minigames (planned)
   └────────┴───────┴─────────┴─────────┴───────────┘
                    │
                MariaDB

 VectoraCloud (planned) starts, scales and watches all of the above.
```

## Proxy (today)

Velocity is the only entry point (Java on port 25565, Bedrock on 19132/UDP). Geyser translates the Bedrock protocol, Floodgate lets Bedrock players in without a Java account. ViaVersion bridges newer Java clients, ViaBackwards and ViaRewind cover older clients down to 1.8. Velocity forwards player data to the backends in `modern` mode. See [Crossplay](Crossplay).

## Backends (today)

Paper for Lobby, Survival, Creative and End; Fabric for the mod server. Backends run the newest Minecraft version. Details and ports: [Server Network](Server-Network).

## Templates and runtime (stage 1)

Each server has two parts:

- its template: configs with placeholders, tracked in git under `templates/<Server>/`
- the service folder: jars, worlds, logs and generated files, in the network folder (the cloud home) outside the repository. VectoraCloud keeps the template of a wanted server at `<home>/local/templates/<Server>/default/` and renders it into `<home>/local/services/<id>` (static services, kept) or `<home>/temp/services/<id>_<run>` (dynamic services, deleted on stop). Configuration is in `<home>/config/`.

The cloud home is the folder VectoraCloud runs in. In production it lives outside any checkout of the repository. For development, `./gradlew :cloud:node:runLocal` builds `VectoraCloud.jar` and starts it with `run/` in the repository root as the cloud home; Git ignores `run/` as a whole, secrets and worlds included.

Until stage 2 the existing servers run locally from a `runtime/` folder via the Windows start scripts. VectoraCloud does not use that folder name; its layout is described in [Cloud home](https://github.com/VectoDE/VectoraCloud/blob/main/docs/cloud/architecture.md#cloud-home).

## VectoraCloud (planned, stage 2)

A Java node with a console (JLine) and a setup wizard.

- **Setup and configuration:** like CloudNet. On first start a setup wizard runs in the console; after that you configure the cloud through config files and console commands (stage 2). Later the web dashboard can do all of it too, including the first-time setup and the choice of servers (stage 8). Both use the same configuration layer, which stage 2 builds.
- **Choosing servers:** in the wizard or the cloud config you mark each of the seven servers and each minigame group as wanted or not. The cloud only builds wanted servers: it writes the server's template (`templates/<Server>/` in the repository) to `local/templates/<Server>/default/`, renders it into the service folder, downloads the software, fills in the secrets and starts the server. Unwanted servers are never created, and neither the proxy nor the lobby refers to them.
- **Services:** groups and tasks, static and dynamic services, autoscaling by player count, multiple nodes in a cluster: one head with its own CA, one-time join tokens, mutual TLS 1.3 between the nodes.
- **Downloads:** Paper and Velocity via the PaperMC API, Fabric meta, the Geyser and Floodgate download API, Hangar and Modrinth for the Via plugins and performance mods. Checksums are verified and versions pinned; updates run a test start and roll back on failure.
- **Java runtimes:** JREs from the Adoptium API, per server group matching the Minecraft version (8, 17, 21 or 25).
- **Database:** the wizard offers three ways: download and manage MariaDB (Windows/Linux), start it in Docker, or use an existing MariaDB/MySQL. H2 is the fallback for tests. Connection pools are sized centrally so `Too many connections` does not come back.
- **Secrets:** generated on first start into `secrets/` or `.env` (gitignored): Velocity forwarding secret, Floodgate key, DB passwords, API tokens, Discord token.
- **Messaging:** Vectora's own channel (Netty/WebSocket) between cloud, proxy and servers.
- **API:** REST and WebSocket with token auth.
- **Backups and monitoring (stage 2):** backups of worlds and database, monitoring of TPS, RAM and players with alerts.
- **Extras (stage 8):** web dashboard (including the full setup), Discord bot, website and cosmetic store. The operator hosts them and brings their own bot token and Stripe account.
- **Operation:** Docker image on GHCR (Dockerfile and compose file in the repository), Pterodactyl egg, systemd unit, start scripts for Windows, Linux and macOS.

## Vectora plugin (planned, stage 3)

Several Gradle modules (core, permissions, social, moderation, stats, economy, survival, creative, minigames, anti-cheat, lobby, portal bridge) are bundled into one plugin per platform:

- `Vectora-Paper.jar`
- `Vectora-Velocity.jar`
- `Vectora-Fabric.jar`

The cloud config decides which modules a server group enables, and the cloud ships the matching jar. Version adapters cover Minecraft 1.8.8 to 26.3. The plugin replaces LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal and Chunky.

Today the repository contains two early modules, `plugins/lobby/paper` and `plugins/portal/paper`, which the Gradle build compiles.

## Build

Gradle with Kotlin DSL, wrapper and toolchains. The toolchain is Java 25, the bytecode target Java 21. CI builds and tests with JDK 25 and runs the tests again on a Java 21 runtime. Code that needs Java 25 carries `@RequiresJava25`, checks the runtime and keeps a Java 21 path.

## Allowed third-party software

Paper, Velocity, Fabric and Fabric API; Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind; Lithium, FerriteCore, ServerCore and FabricProxy-Lite on the mod server; MariaDB/MySQL. For 1.8.8 server groups, stage 2 will pick a modern fork that runs on Java 21.
