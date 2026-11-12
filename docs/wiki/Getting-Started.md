# Getting Started

[Deutsch](Getting-Started-DE)

From a download to a running network. This is a pre-release: v0.1.1-dev is for trying things out, not for production. Per-platform details are in [Operations](Operations).

## What you need

- Java 21 or newer. The cloud itself runs on it; the servers it starts get the Java version their Minecraft version needs, and the cloud downloads a matching runtime if none is installed.
- A normal user account. The cloud refuses to start as root or administrator.
- Internet access on the first start, so the cloud can download the server software.
- Memory for the servers you choose. The setup assistant adds up the heaps and warns if they exceed the machine.

## 1. Download and check

Take the archive for your system from the [latest release](https://github.com/VectoDE/VectolynCloud/releases): `VectolynCloud-<version>-windows.zip` or `VectolynCloud-<version>-linux-macos.tar.gz`. Check it against `SHA256SUMS` and the signature, see [Checking a download](Operations#checking-a-download).

## 2. Unpack and start

Unpack the archive into an empty folder. This folder is the cloud home: configuration, worlds, database and downloads all end up inside it.

| System | Start |
|---|---|
| Windows | `start.bat` in a normal Command Prompt or PowerShell (not "Run as administrator") |
| Linux, macOS | `./start.sh` |
| Docker | `docker run -it --init -v vectolyn:/data -p 25565:25565 -p 19132:19132/udp ghcr.io/vectode/vectolyn-cloud:<version>` |
| Pterodactyl | import the egg, see [Operations](Operations) |

## 3. Answer the setup assistant

On the first start the cloud asks, in this order: language, Minecraft EULA, network name, which servers you want (proxy, lobby, Survival, Creative, mod server, End, minigames, legacy proxy), ports, memory per server, number of services and the database (managed MariaDB, Docker, an existing MariaDB/MySQL, or H2). Enter takes the default in brackets. At the end it shows a summary and writes nothing until you choose `save`. Without your consent to the EULA no server starts.

Without a terminal (systemd, Docker without `-it`, Pterodactyl) the assistant reads a setup file instead: `./start.sh --non-interactive --setup-file setup.yml`. The format is described in [docs/cloud/setup.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.md).

## 4. Let it run

The cloud generates the secrets, downloads and checks the server software and plugins, renders each wanted server from its template into its own folder under `local/services/` and starts it. The start prints a banner and one line per step. Servers you did not choose are not created, and neither proxy nor lobby refers to them.

Connect with a Java client to `localhost:25565` or with a Bedrock client to `localhost`, port `19132`. Ports of all servers: [Server Network](Server-Network).

In the console, `help` lists the commands, `service list` shows the services and `shutdown` stops everything. After the first setup you change the configuration through files and console commands, see [Configuration](Configuration). Scripts can use the [API](API).

## Where things are

Back up `config/`, `local/` and `secrets/`. Downloads, Java runtimes and logs come back on their own. The full layout: [Operations](Operations#where-the-files-are).

## Try a change from the repository

To try the current state of the code or a change of your own:

```sh
./gradlew :cloud:node:runLocal --console=plain
```

This builds `VectolynCloud.jar` and starts it with `run/` in the repository root as the cloud home. The setup assistant asks in the same terminal; everything stays in `run/`, which Git ignores. Stop with `shutdown`, delete `run/` to start over. If your shell runs as administrator or root, add `-PallowRoot=true`. `run/` is for development only. Details: [docs/cloud/launcher.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/launcher.md#local-start-from-the-repository).

## Building the plugins

```sh
./gradlew build        # Linux, macOS, Git Bash
gradlew.bat build      # Windows cmd / PowerShell
```

Gradle starts on Java 17 or newer and compiles with a Java 25 toolchain, which it downloads if missing. The bytecode target is Java 21. The jars land in `plugins/lobby/paper/build/libs/` and `plugins/portal/paper/build/libs/`. Details: [Java compatibility](https://github.com/VectoDE/VectolynCloud/blob/main/docs/java-compatibility.md).

## Plugin reference

Both plugins have no commands and no config you need to touch. This is what they do today. They are replaced by the Vectolyn plugin in stages 3 and 4.

### VectolynLobby

Install on the Lobby server.

- On the first start it builds the sky-island lobby around `0, 100, 0` in the server's first world, then writes `built: true` to `plugins/VectolynLobby/config.yml`. Set it back to `false` to build the island again on the next start; existing blocks in that area are overwritten.
- On every start it turns off the daylight and weather cycle in that world and sets the spawn point onto the platform.
- Players are teleported to the spawn when they join. They take no damage and get no hunger.
- Breaking and placing blocks is blocked unless the player has the permission `vectolyn.lobby.build`. `plugin.yml` gives it to operators only (`default: op`); other players need it from a permission plugin.

### VectolynPortalBridge

Install on Lobby, Survival and Creative.

- When a player enters an End portal, the plugin cancels the local teleport and sends a `Connect` message on the `BungeeCord` plugin channel, which moves the player to the backend named `end`.
- This needs `bungee-plugin-message-channel = true` in `velocity.toml` (set in the proxy template) and a server called `end` in its `[servers]` list.
- If the message cannot be sent, the player sees "The End server is currently unavailable." and the server log gets a warning.
