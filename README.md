# Vectora Network

[![CI](https://github.com/VectoDE/Vectora-Network/actions/workflows/ci.yml/badge.svg)](https://github.com/VectoDE/Vectora-Network/actions/workflows/ci.yml)
[![CodeQL](https://github.com/VectoDE/Vectora-Network/actions/workflows/codeql.yml/badge.svg)](https://github.com/VectoDE/Vectora-Network/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/VectoDE/Vectora-Network/badge)](https://scorecard.dev/viewer/?uri=github.com/VectoDE/Vectora-Network)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16026/badge)](https://www.bestpractices.dev/projects/16026)
[![License: GPL-3.0](https://img.shields.io/github/license/VectoDE/Vectora-Network)](LICENSE)

**English** · [Deutsch](README.de.md)

Vectora is a Minecraft network where Java players (1.8 up to the latest version) and Bedrock players play together. The whole network is open source under GPL-3.0, so you can also use it as a kit for your own network.

The long-term goal: you run `java -jar VectoraCloud.jar` on Windows, Linux, macOS, in Docker or on Pterodactyl, answer a setup wizard, and the complete network comes up with database, proxy, lobby, game modes and minigames.

## Status

**Stage 1 of 8: Foundation.** We are moving the existing servers into this repository, taking secrets out, fixing known config problems and setting up the Gradle build. There is no public server and no release yet. Most of what this README describes under *Architecture* is the plan, not working code.

- Roadmap and progress: [Vectora Roadmap](https://github.com/users/VectoDE/projects/11)
- Current stage: [#1 Stage 1: Foundation](https://github.com/VectoDE/Vectora-Network/issues/1)

| Stage | Content |
|---|---|
| 1 | Foundation: repository, build, config fixes |
| 2 | VectoraCloud core |
| 3 | Vectora plugin base: core, permissions, messaging, portals/End, sync, version adapters |
| 4 | Lobby |
| 5 | Survival, Creative, Mod server |
| 6 | Minigames and anti-cheat |
| 7 | Brand and resource pack |
| 8 | Dashboard, Discord bot, website, store |

## Architecture

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

- **Proxy:** Velocity is the single entry point. Geyser and Floodgate let Bedrock clients in, the Via plugins translate older Java versions. Backends run the newest Minecraft version.
- **Backends:** Paper for Lobby, Survival, Creative, End and (later) Minigames. Fabric for the mod server.
- **VectoraCloud** (planned, stage 2): a Java node with console and setup wizard. It downloads and verifies server software, manages JREs and the database, generates secrets, scales minigame servers and offers a REST/WebSocket API. Setup works like CloudNet: a console wizard on first start, then config files and console commands (stage 2). The web dashboard will cover everything as well, first-time setup included (stage 8). In the wizard, the config or later the dashboard you mark each of the seven servers and each minigame group as wanted or not. The cloud only builds wanted servers: it copies `Server-Network/<Server>/template/` to `runtime/`, downloads the software, fills in the secrets and starts them. Servers you do not want are never created, and neither the proxy nor the lobby refers to them.
- **One Vectora plugin** (planned, stage 3): several Gradle modules (core, permissions, social, moderation, stats, economy, lobby, portals and more) bundled into one jar per platform: `Vectora-Paper.jar`, `Vectora-Velocity.jar`, `Vectora-Fabric.jar`. It will replace LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal and Chunky.

Third-party software is limited to a necessary base: Paper, Velocity, Fabric and Fabric API, Geyser, Floodgate, ViaVersion, ViaBackwards, ViaRewind, the performance mods Lithium, FerriteCore, ServerCore and FabricProxy-Lite, and MariaDB/MySQL.

More detail: [Architecture in the wiki](https://github.com/VectoDE/Vectora-Network/wiki/Architecture).

## Repository layout

```
Vectora-Network/
  Server-Network/
    Proxy/ Lobby-Server/ Survival-Server/ Creative-Server/ Mod-Server/ End-Server/ Database/
      template/           configs with placeholders, tracked in git
      runtime/            jars, worlds, logs; local only, gitignored
    secrets/              local secrets, gitignored except vectora.env.example
  Vectora-Lobby/          plugin module: lobby
  Vectora-PortalBridge/   plugin module: portals and End
  docs/                   project docs; docs/wiki/ is mirrored into the GitHub wiki
  .github/                workflows, issue forms, Dependabot
```

Planned: `VectoraCloud/`, more plugin modules, `Website/`, `Resourcepack/`.

## Quick start (transitional, until VectoraCloud exists)

Until stage 2 the servers run on Windows from `Server-Network/<Server>/runtime/` through start scripts. Picking which servers you want only becomes possible with VectoraCloud; today's launcher starts all of them. You need Java 25 on the `PATH` (Paper 26.3 requires it) and the portable MariaDB 11.8.9 for Windows.

1. Clone the repository.
2. Copy each `Server-Network/<Server>/template/` to `Server-Network/<Server>/runtime/`.
3. Copy `Server-Network/secrets/vectora.env.example` to `Server-Network/secrets/vectora.env` and fill in your own values. Never commit this file.
4. Replace every `{{VECTORA_…}}` placeholder in the `runtime/` folders with your values and create the generated files (`forwarding.secret`, Floodgate `key.pem`, `eula.txt`). [`Server-Network/placeholders.md`](Server-Network/placeholders.md) lists every placeholder. VectoraCloud will do this step for you.
5. Put the server software into each `runtime/` folder. The repository ships no jars. The start scripts expect `velocity.jar`, `paper.jar` and `fabric-server.jar`; exact versions and plugin files are listed in [Getting Started](docs/wiki/Getting-Started.md).
6. Run `Server-Network\start-network.bat` and choose:
   - `1` everything: database, then proxy and all five backends
   - `2` database only (same as `Server-Network\start-vectora-database.cmd`)
   - `3` proxy only
   - `4` backends only (Lobby, Survival, Mod, Creative, End)
   - `5` quit

Default ports of the current setup:

| Service | Port |
|---|---|
| Proxy, Java | 25565 |
| Proxy, Bedrock (Geyser, UDP) | 19132 |
| Lobby | 25566 |
| Survival | 25567 |
| Mod server | 25568 |
| Creative | 25569 |
| End | 25570 |
| MariaDB | 3307 |

Only the proxy ports should be reachable from outside.

## Build

```sh
./gradlew build
```

On Windows use `gradlew.bat build`. The Gradle wrapper downloads Gradle itself.

- Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain. If no JDK 25 is installed, Gradle downloads one (Foojay resolver).
- The bytecode target is Java 21 (`--release 21`). The servers themselves need Java 25, because Paper 26.3 does.
- CI builds and runs all tests with JDK 25 on Linux and Windows, and runs the tests a second time on a Java 21 runtime. Tests that load Paper classes are skipped there; see [docs/java-compatibility.md](docs/java-compatibility.md).
- Code that needs Java 25 gets `@RequiresJava25` and keeps a Java 21 path.

## Documentation and community

- [Wiki](https://github.com/VectoDE/Vectora-Network/wiki) (English and German, sources in `docs/wiki/`)
- [Discussions](https://github.com/VectoDE/Vectora-Network/discussions) for questions, ideas and announcements
- [Contributing](CONTRIBUTING.md) · [Code of Conduct](CODE_OF_CONDUCT.md) · [Security](SECURITY.md)

## License

[GPL-3.0](LICENSE). Minecraft is a trademark of Mojang Synergies AB. Vectora is not affiliated with Mojang or Microsoft.
