# FAQ

[Deutsch](FAQ-DE)

**Is there an official Vectora server I can join?**
No, and there won't be one. Vectora is a kit: we publish the code and releases, and every operator runs their own network. The project is in stage 1 of 8; releases are announced in [Discussions → Announcements](https://github.com/VectoDE/Vectora-Network/discussions/categories/announcements).

**Which Minecraft versions can play?**
The goal is Java 1.8 to 26.3 plus Bedrock. See [Crossplay](Crossplay) for what works today.

**Do Bedrock players get the full game?**
That is a project rule: no game mode ships that Bedrock players cannot enter. Menus are inventory menus that work on both editions.

**Do I have to run all seven servers?**
No, once VectoraCloud exists (planned, stage 2). You pick the servers and minigame groups you want in the setup wizard or the config; the cloud only creates those. Until then the transitional setup starts the existing servers.

**How will VectoraCloud be configured?**
Like CloudNet: a console wizard on first start, then config files and console commands (stage 2). The web dashboard will be able to do all of it as well (stage 8).

**Which Java version do I need?**
To run the servers: Java 25, because Paper 26.3 requires it. To build: Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain that it downloads if missing. The plugin bytecode targets Java 21, and anything that needs Java 25 keeps a Java 21 path.

**Why not just use LuckPerms, WorldEdit, TAB and friends?**
Vectora wants one plugin per platform that works the same on every server and knows about Bedrock. The plan replaces LuckPerms, TAB, WorldEdit, WorldGuard, PlaceholderAPI, ProxyPortal and Chunky with its own modules. Until those exist, the current servers still use some of them.

**Can I use the code for my own network?**
Yes, that is what it is for, within the terms of GPL-3.0. If you distribute a modified version, you have to publish the source of your changes under the same license.

**Will there be pay-to-win?**
No. The planned store sells cosmetics only and follows the Minecraft EULA. We develop it against Stripe test mode. Each operator connects their own Stripe account and is responsible for the legal side of taking real payments.

**Where do I report a security problem?**
Privately, see [SECURITY.md](https://github.com/VectoDE/Vectora-Network/blob/main/SECURITY.md).

More questions: [Q&A in Discussions](https://github.com/VectoDE/Vectora-Network/discussions/categories/q-a).
