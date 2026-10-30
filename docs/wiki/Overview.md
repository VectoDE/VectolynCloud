# Overview

[Deutsch](Overview-DE)

Vectolyn is an open-source kit under GPL-3.0 for running your own Minecraft network. On a Vectolyn network, Java players from 1.8 to 26.3 and Bedrock players join the same proxy and play together: lobby, survival, creative, a Fabric mod server, a shared End server and minigames. Everything the network needs lives in this repository.

We do not run a network, servers or a domain, and we host no website, Discord bot or store. The website, Discord bot and store are built as code in this repository. As the operator you configure them with your own bot token and your own Stripe account and host them yourself.

## Status

The project is in **stage 2 of 8** (VectolynCloud core). Stage 1, the foundation, is finished and out as the signed pre-release [v0.1.0](https://github.com/VectoDE/VectolynCloud/releases/tag/v0.1.0). There is no usable cloud release yet. Progress: [Roadmap](Roadmap) and [#11](https://github.com/VectoDE/VectolynCloud/issues/11).

## Goal

You run `java -jar VectolynCloud.jar` on Windows, Linux, macOS, in Docker or on Pterodactyl. A setup wizard asks a few questions, including which servers and minigame groups you want. After that the network runs with database, proxy, lobby, game modes and minigames, without further manual work. A release will contain `VectolynCloud.jar`, start scripts for Windows, Linux and macOS, and a Docker image on GHCR; the Dockerfile, compose file and Pterodactyl egg live in the repository. This is the target for stage 2 onwards, not the current state.

## Principles

- **Bedrock is a first-class client.** No game mode that Bedrock players cannot enter. Menus are inventory menus that work on both editions.
- **Few third-party plugins.** Only a necessary base (server software, crossplay and version bridges, a few performance mods, MariaDB). Permissions, tab list, build tools, region protection, placeholders, portals and pregeneration will be written in this repository.
- **No secrets in git.** Forwarding secrets, keys, passwords and tokens are generated locally and stay out of the repository.
- **Bilingual.** Ingame texts follow the client language (English or German). README, wiki and discussions exist in both languages, and so will the website code.
- **Bytecode for Java 21.** Code that needs Java 25 is marked and keeps a Java 21 path. The current servers run on Java 25 because Paper 26.3 requires it.
- **Fair store.** The planned store sells cosmetics only.

Next: [Architecture](Architecture) · [Server Network](Server-Network)
