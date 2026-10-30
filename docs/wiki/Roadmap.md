# Roadmap

[Deutsch](Roadmap-DE)

Vectolyn is built in eight stages. Each stage gets its own plan and ends in a runnable, tested and pushed state. Live status: [Vectolyn Roadmap project](https://github.com/users/VectoDE/projects/11) and the [milestones](https://github.com/VectoDE/VectolynCloud/milestones).

**Current stage: 2.** Stage 1 is done, stage 2 is in progress, everything after it is planned.

## 1. Foundation ([#1](https://github.com/VectoDE/VectolynCloud/issues/1))

**Status: done.** Released as the signed pre-release [v0.1.1](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.1).

Stop the servers and back up worlds and state locally; move the server folders to `Server-Network/` with a template/runtime split (since replaced by `templates/<Server>/` in the repository and the cloud home outside it); adjust start scripts and ini paths; `.gitignore`; move secrets out and regenerate them; fix known problems (Floodgate on Fabric, ViaBackwards/ViaRewind, DB pools, ini paths); Gradle build for the existing plugins; repository basics; GitHub setup; first push after a gitleaks check.

## 2. VectolynCloud core ([#11](https://github.com/VectoDE/VectolynCloud/issues/11))

**Status: in progress.**

Node with console, CloudNet-style setup wizard on first start, configuration through files and console commands, and one shared configuration layer that the dashboard will reuse in stage 8. Choice of wanted servers and minigame groups; only wanted ones are built from their templates. Groups and tasks, autoscaling, multi-node over TLS, REST/WebSocket API, platform downloader with checksums and rollback, JRE management, database wizard, secret generation, own messaging channel, backups and monitoring, Docker image on GHCR with Dockerfile and compose file, Pterodactyl egg, systemd unit, start scripts for Windows, Linux and macOS. Research a 1.8.8 fork for Java 21. Planned safety rule: VectolynCloud refuses to start as administrator or root unless an explicit switch (such as `--allow-root`) is given, and the message explains how to start it without admin rights.

## 3. Vectolyn plugin base ([#12](https://github.com/VectoDE/VectolynCloud/issues/12))

Core, permissions and ranks, messaging, portals and End, sync between servers, version adapters for 1.8.8 to 26.3. One jar per platform.

## 4. Lobby ([#13](https://github.com/VectoDE/VectolynCloud/issues/13))

Code-generated hub lobby with navigator, NPCs and portals, hologram leaderboards, gadgets, profile menu, protection, double jump, parkour.

## 5. Survival, Creative, Mod server ([#14](https://github.com/VectoDE/VectolynCloud/issues/14))

Survival with claims, homes, shops, jobs and economy; Creative with plots, build tool and freebuild; Fabric mod server with an approved mod list; the shared End server.

## 6. Minigames and anti-cheat ([#15](https://github.com/VectoDE/VectolynCloud/issues/15))

BedWars, SkyWars, Parkour/JumpLeague, SurvivalGames, TNTRun/Spleef, BuildBattle, MurderMystery/TTT with generated maps; own anti-cheat that handles Bedrock movement.

## 7. Brand and resource pack ([#16](https://github.com/VectoDE/VectolynCloud/issues/16))

New brand (logo, palette, typeface, tone), approved before use. Resource pack in steps, with a build tool for every Java `pack_format` from 1.8 to 26.3 plus a Bedrock pack.

## 8. Dashboard, Discord bot, website, store ([#17](https://github.com/VectoDE/VectolynCloud/issues/17))

Web dashboard that can do everything the console can, first-time setup and server choice included; Discord bot; website; cosmetic-only store with Stripe, developed in test mode. All three are code in this repository: operators configure them with their own bot token and Stripe account and host them themselves. We host nothing.

## Open questions

- Which 1.8.8 fork runs on Java 21 (stage 2).
- Mod list for the mod server (proposal and approval).
- Brand draft (approval).

Feedback: [Roadmap discussion](https://github.com/VectoDE/VectolynCloud/discussions/20).
