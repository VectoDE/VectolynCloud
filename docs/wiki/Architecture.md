# Architecture

[Deutsch](Architecture-DE)

> Most of this page describes the planned target. Parts that exist today are marked **today**.

## Layers

```
 Java 1.8 to latest       Bedrock
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

Each server under `Server-Network/` has two parts:

- `template/`: configs with placeholders, tracked in git
- `runtime/`: jars, worlds, logs and generated files, local only and gitignored

Until stage 2 the existing servers run locally from `runtime/` via the Windows start scripts.

## VectoraCloud (planned, stage 2)

A Java node with a console (JLine) and a setup wizard.

- **Setup and configuration:** like CloudNet. On first start a setup wizard runs in the console; after that you configure the cloud through config files and console commands (stage 2). Later the web dashboard can do all of it too, including the first-time setup and the choice of servers (stage 8). Both use the same configuration layer, which stage 2 builds.
- **Choosing servers:** in the wizard or the cloud config you mark each of the seven servers and each minigame group as wanted or not. The cloud only builds wanted servers: it copies `Server-Network/<Server>/template/` to `runtime/`, downloads the software, fills in the secrets and starts the server. Unwanted servers are never created, and neither the proxy nor the lobby refers to them.
- **Services:** groups and tasks, static and dynamic services, autoscaling by player count, multiple nodes in a cluster over TLS.
- **Downloads:** Paper and Velocity via the PaperMC API, Fabric meta, the Geyser and Floodgate download API, Hangar and Modrinth for the Via plugins and performance mods. Checksums are verified and versions pinned; updates run a test start and roll back on failure.
- **Java runtimes:** JREs from the Adoptium API, per server group matching the Minecraft version (8, 17, 21 or 25).
- **Database:** the wizard offers three ways: download and manage MariaDB (Windows/Linux), start it in Docker, or use an existing MariaDB/MySQL. H2 is the fallback for tests. Connection pools are sized centrally so `Too many connections` does not come back.
- **Secrets:** generated on first start into `secrets/` or `.env` (gitignored): Velocity forwarding secret, Floodgate key, DB passwords, API tokens, Discord token.
- **Messaging:** Vectora's own channel (Netty/WebSocket) between cloud, proxy and servers.
- **API:** REST and WebSocket with token auth.
- **Backups and monitoring (stage 2):** backups of worlds and database, monitoring of TPS, RAM and players with alerts.
- **Extras (stage 8):** web dashboard (including the full setup) and Discord bot.
- **Operation:** Docker image, Pterodactyl egg, systemd unit, start scripts for Windows and macOS.

## Vectora plugin (planned, stage 3)

Several Gradle modules (core, permissions, social, moderation, stats, economy, survival, creative, minigames, anti-cheat, lobby, portal bridge) are bundled into one plugin per platform:

- `Vectora-Paper.jar`
- `Vectora-Velocity.jar`
- `Vectora-Fabric.jar`

The cloud config decides which modules a server group enables, and the cloud ships the matching jar. Version adapters cover Minecraft 1.8.8 to latest. The plugin replaces LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal and Chunky.

Today the repository contains two early modules, `Vectora-Lobby` and `Vectora-PortalBridge`, which the Gradle build compiles.

## Build

Gradle with Kotlin DSL, wrapper and toolchains. The toolchain is Java 25, the bytecode target Java 21. CI builds and tests with JDK 25 and runs the tests again on a Java 21 runtime. Code that needs Java 25 carries `@RequiresJava25`, checks the runtime and keeps a Java 21 path.

## Allowed third-party software

Paper, Velocity, Fabric and Fabric API; Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind; Lithium, FerriteCore, ServerCore and FabricProxy-Lite on the mod server; MariaDB/MySQL. For 1.8.8 server groups, stage 2 will pick a modern fork that runs on Java 21.
