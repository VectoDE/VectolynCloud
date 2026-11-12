# FAQ

[Deutsch](FAQ-DE)

**Is there an official Vectolyn server I can join?**
No, and there won't be one. Vectolyn is a kit: we publish the code and releases, and every operator runs their own network. The project is in stage 2 of 8 (see [Roadmap](Roadmap) and [Feature status](Feature-Status)); releases are announced in [Discussions → Announcements](https://github.com/VectoDE/VectolynCloud/discussions/categories/announcements).

**Which Minecraft versions can play?**
The goal is Java 1.8 to 26.3 plus Bedrock. See [Crossplay](Crossplay) for what works today.

**Do Bedrock players get the full game?**
That is a project rule: no game mode ships that Bedrock players cannot enter. Menus are inventory menus that work on both editions.

**Do I have to run all seven servers?**
No. You pick the servers and minigame groups you want in the setup assistant or the config; the cloud only creates those.

**How will VectolynCloud be configured?**
A console wizard on first start, then config files and console commands (stage 2). The web dashboard will be able to do all of it as well (stage 8).

**Which Java version do I need?**
To run the servers: Java 25, because Paper 26.3 requires it. To build: Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain that it downloads if missing. The plugin bytecode targets Java 21, and anything that needs Java 25 keeps a Java 21 path.

**Why not just use LuckPerms, WorldEdit, TAB and friends?**
Vectolyn wants one plugin per platform that works the same on every server and knows about Bedrock. The plan replaces LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal and Chunky with its own modules. Until those exist, the current servers still use some of them.

**Can I use the code for my own network?**
Yes, that is what it is for, within the terms of GPL-3.0. If you distribute a modified version, you have to publish the source of your changes under the same license.

**Will there be pay-to-win?**
No. The planned store sells cosmetics only and follows the Minecraft EULA. We develop it against Stripe test mode. Each operator connects their own Stripe account and is responsible for the legal side of taking real payments.

## Problems at the start

**The cloud stops at once with exit code 2.**
It was started as root or administrator, which it refuses. Start it from a normal user account (on Windows a normal console, not "Run as administrator"). The message explains how for your system. `--allow-root` or `VECTOLYN_ALLOW_ROOT=true` allow it anyway; use that only if there is no other way.

**"VectolynCloud needs Java 21 or newer."**
The Java on your `PATH` is older. Install Java 21 or newer (for example Temurin from <https://adoptium.net/temurin/releases/>), or point `JAVA_HOME` at it. The message names the version and path it found.

**No server starts and the log says the setup is not complete.**
Until `setup-complete: true` is in `config/cloud.yml`, the cloud starts no server. Answer the setup assistant, or run it with `--non-interactive --setup-file setup.yml` where there is no terminal. The Minecraft EULA has to be accepted as well.

**PowerShell refuses to run `start.ps1`.**
Windows blocks scripts downloaded from the internet. Run `Unblock-File .\start.ps1` once, or use `start.bat`, which needs neither.

**Under systemd or in Docker I have no console.**
Without a terminal the cloud has no console and keeps running. Use the [API](API) for commands, or run the cloud in `tmux` or `screen` as its own user when you need the console.

**On macOS (or an ARM server) the database is H2, not MariaDB.**
MariaDB publishes no server build for these systems, so `managed` mode falls back to the embedded H2 database and says so in the log. For a shared database use `external` or Docker.

**On Pterodactyl several servers fail with "unable to create native thread".**
Wings limits each container to 512 processes and threads by default. Raise `docker.container_pid_limit` in `/etc/pterodactyl/config.yml`, for example to 4096. Details in [Operations](Operations).

**Where are the logs?**
`logs/cloud.log` in the cloud home, and `logs/services/<id>.log` for each server. Older runs and days are kept as `cloud-<date>.log` and `<id>-<date>.log` for 14 days; `logs.retention-days` in `cloud.yml` changes that.

**Where do I report a security problem?**
Privately, see [SECURITY.md](https://github.com/VectoDE/VectolynCloud/blob/main/SECURITY.md).

More questions: [Q&A in Discussions](https://github.com/VectoDE/VectolynCloud/discussions/categories/q-a).
