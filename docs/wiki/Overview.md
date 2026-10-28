# Overview

[Deutsch](Overview-DE)

Vectora has two sides:

1. **A public Minecraft network.** Java players from 1.8 to 26.3 and Bedrock players join the same proxy and play together: lobby, survival, creative, a Fabric mod server, a shared End server and minigames.
2. **An open-source kit.** Everything the network needs lives in this repository under GPL-3.0. You can take it and run your own network.

## Status

The project is in **stage 1 of 8** (foundation). The existing servers are moving into the repository, secrets are leaving the tree, and the Gradle build is being set up. There is no public server and no release yet. See [Roadmap](Roadmap).

## Goal

You run `java -jar VectoraCloud.jar` on Windows, Linux, macOS, in Docker or on Pterodactyl. A setup wizard asks a few questions, including which servers and minigame groups you want. After that the network runs with database, proxy, lobby, game modes and minigames, without further manual work. This is the target for stage 2 onwards, not the current state.

## Principles

- **Bedrock is a first-class client.** No game mode that Bedrock players cannot enter. Menus are inventory menus that work on both editions.
- **Few third-party plugins.** Only a necessary base (server software, crossplay and version bridges, a few performance mods, MariaDB). Permissions, tab list, build tools, region protection, placeholders, portals and pregeneration will be written in this repository.
- **No secrets in git.** Forwarding secrets, keys, passwords and tokens are generated locally and stay out of the repository.
- **Bilingual.** Ingame texts follow the client language (English or German). README, wiki, discussions and website exist in both languages.
- **Bytecode for Java 21.** Code that needs Java 25 is marked and keeps a Java 21 path. The current servers run on Java 25 because Paper 26.3 requires it.
- **Fair store.** The planned store sells cosmetics only.

Next: [Architecture](Architecture) · [Server Network](Server-Network)
