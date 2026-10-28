# Getting Started

[Deutsch](Getting-Started-DE)

There are two ways to start: the transitional setup that exists today, and the VectoraCloud setup that is planned for stage 2.

## Today: transitional setup (Windows)

Until VectoraCloud exists, the servers run locally from `Server-Network/<Server>/runtime/` through Windows start scripts. The launcher always starts all servers; choosing servers comes with VectoraCloud.

You need:

- Java 25 on the `PATH`. Paper 26.3 and the Fabric server for 26.3 require it, and Velocity runs on it too.
- The portable MariaDB 11.8.9 for Windows x64 (ZIP archive).

### 1. Create the runtime folders

1. Clone the repository.
2. Copy each `Server-Network/<Server>/template/` to `Server-Network/<Server>/runtime/`. `runtime/` is gitignored.
3. Copy `Server-Network/secrets/vectora.env.example` to `Server-Network/secrets/vectora.env` and fill in your own values. Never commit this file.
4. Replace every `{{VECTORA_…}}` placeholder in the `runtime/` folders with your values. [`placeholders.md`](https://github.com/VectoDE/Vectora-Network/blob/main/Server-Network/placeholders.md) lists each placeholder, where it is used and its local default (ports, heap sizes, the proxy's server list).
5. Create the files that do not come from templates: `Proxy/runtime/forwarding.secret` (the forwarding secret), the Floodgate `key.pem` (the base64 value decoded to 16 bytes, the same file on the proxy and on every backend) and `eula.txt` once you have accepted the Minecraft EULA.

VectoraCloud takes over steps 2 to 5 in stage 2.

### 2. Add the server software

The repository ships no jars. The current setup runs with these versions. The start scripts expect the file names in the second column.

| Folder (`runtime/`) | Server jar | Plugins or mods |
|---|---|---|
| `Proxy` | `velocity.jar` (Velocity 4.3.0) | `plugins/`: Geyser-Velocity, Floodgate (Velocity), LuckPerms-Velocity 5.5.71, ViaVersion 5.12.2, ViaBackwards 5.12.2, ViaRewind 4.2.0 |
| `Lobby-Server`, `Survival-Server`, `Creative-Server`, `End-Server` | `paper.jar` (Paper 26.3) | `plugins/`: Floodgate (Spigot), LuckPerms-Bukkit 5.5.71, TAB 6.2.1, PlaceholderAPI 2.12.3, WorldEdit 7.4.6, WorldGuard 7.0.20 |
| `Mod-Server` | `fabric-server.jar` (Fabric Loader 0.20.1, MC 26.3) | `mods/`: Fabric API, FabricProxy-Lite 2.12.0, Floodgate-Fabric 2.2.7-b69, LuckPerms-Fabric 5.5.85, Lithium 0.26.2+mc26.3, FerriteCore 9.0.0, ServerCore 1.5.20+26.3, Chunky 1.5.3, spark 1.10.187, ProxyPortal 2.0.0+26.1 |
| `Database` | MariaDB 11.8.9, unpacked to `mariadb-11.8.9-winx64/` | none |

The Vectora plugins are built from this repository (see *Building the plugins* below). `VectoraLobby` goes on the Lobby, `VectoraPortalBridge` on Lobby, Survival and Creative.

Before the first database start, create the data folder `Database/runtime/data` with `mariadb-install-db.exe` from MariaDB's `bin` folder. Then run the filled-in `init.sql` once as the MariaDB root user. It creates the LuckPerms database and user.

### 3. Start

Run `Server-Network\start-network.bat` and pick an option:

| Option | Starts |
|---|---|
| `1` | Everything: the database, then after five seconds the proxy and all five backends |
| `2` | Database only |
| `3` | Proxy only |
| `4` | Backends only: Lobby, Survival, Mod, Creative, End |
| `5` | Nothing, closes the window |

Each server opens its own window and restarts five seconds after it stops. Close the window to stop it for good. `Server-Network\start-vectora-database.cmd` starts only the database, like option `2`.

Then connect with a Java client to `localhost:25565` or with a Bedrock client to `localhost`, port `19132`.

Ports of all servers: [Server Network](Server-Network).

## Planned: VectoraCloud (stage 2 and later)

VectoraCloud is set up and configured like CloudNet:

1. Run `java -jar VectoraCloud.jar` on Windows, Linux, macOS, in Docker or on Pterodactyl.
2. On first start a setup wizard runs in the console. It asks, among other things, how the database should run (managed MariaDB, Docker or external) and which of the seven servers and minigame groups you want.
3. The cloud generates the secrets, downloads and verifies the server software and Java runtimes, builds each wanted server from `Server-Network/<Server>/template/` in `runtime/` and starts it. Servers you did not choose are not created, and neither proxy nor lobby refers to them.
4. After that you change the setup through config files and console commands.

From stage 8 on, the web dashboard will offer the same, including the first-time setup and the choice of servers. Wizard, files, console commands and dashboard all work on one shared configuration layer.

## Building the plugins

```sh
./gradlew build        # Linux, macOS, Git Bash
gradlew.bat build      # Windows cmd / PowerShell
```

Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain, which it downloads if missing. The bytecode target is Java 21. The jars land in `Vectora-Lobby/build/libs/` and `Vectora-PortalBridge/build/libs/`. Details: [Java compatibility](https://github.com/VectoDE/Vectora-Network/blob/main/docs/java-compatibility.md).
