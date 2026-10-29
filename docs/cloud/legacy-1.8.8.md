# Legacy 1.8.8 groups

Status: spike, checked on 2026-10-28.

## Result

PandaSpigot, a maintained 1.8.8 fork, starts and answers a 1.8 server list ping on Temurin **Java 21** and on Temurin **Java 25**. That clears the precondition for running 1.8.8 groups on the same Java runtime as the rest of the network.

The weekly workflow [`legacy-fork-smoke.yml`](../../.github/workflows/legacy-fork-smoke.yml) repeats the Java 21 check against the newest build, so a regression in the fork shows up without anyone looking.

## What was tested

| | |
|---|---|
| Server | PandaSpigot build **226** (`git-PandaSpigot-226`, commit `1afa565`, built 2026-09-29), MC 1.8.8 |
| Download | `https://downloads.hpfxd.com/v2/projects/pandaspigot/versions/1.8.8/builds/226/downloads/paperclip` |
| sha256 | `ac73146b275230cbd0612ef0f3d6b40333abd3182beae457cad5321efff6359b`, matches the API metadata |
| Java 21 | Temurin 21.0.12.1+1 (archive sha256 checked against the Adoptium API) |
| Java 25 | Temurin JRE 25.0.5+8 (archive sha256 checked against the Adoptium API) |
| Settings | `online-mode=false`, `spigot.yml` `settings.bungeecord: true`, `restart-on-crash: false` and `restart-script: ''` (restarts are the node's job, see [template](template.md#restarts-are-the-nodes-job)), flat world, bound to 127.0.0.1 |
| Check | wait for `Done (`, status ping with protocol 47 plus ping/pong, `stop`, exit code |
| Host | Windows 11 locally; the workflow runs the same script on `ubuntu-latest` |

The test scripts are [`legacy_fork_smoke.py`](../../.github/scripts/legacy_fork_smoke.py) and [`legacy_status_ping.py`](../../.github/scripts/legacy_status_ping.py). They use the Python standard library only.

### Java 21

```text
[18:23:54 INFO]: This server is running PandaSpigot version git-PandaSpigot-226 (MC: 1.8.8) (Implementing API version 1.8.8-R0.1-SNAPSHOT) (Git: 1afa565)
[18:24:00 INFO]: Done (6,117s)! For help, type "help" or "?"
status ping: PandaSpigot 1.8.8 protocol 47
server exited with code 0
```

No exceptions. The only warnings are the expected offline-mode notice and Log4j's "Advanced terminal features are not available", which comes from running without a TTY.

### Java 25

Same result (`Done (6,111s)`, protocol 47, exit code 0), plus one JVM warning at startup:

```text
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::allocateMemory has been called by io.netty.util.internal.PlatformDependent0$2 (file:/.../cache/patched_1.8.8.jar)
WARNING: sun.misc.Unsafe::allocateMemory will be removed in a future release
```

This is the JDK's warning for the `sun.misc.Unsafe` memory-access methods (JEP 471/498). It is harmless on 25. A later JDK is expected to block these methods by default, and the Netty 4.1 inside PandaSpigot would then have to fall back or fail. Java 21 does not print the warning. Recommendation: run legacy groups on **Java 21** and treat 25 as tested but not the default.

## Architecture

Paper and Velocity's `modern` forwarding need a backend that understands Velocity's login plugin message, which 1.8.8 servers do not. 1.8.8 backends only understand the legacy BungeeCord handshake. So the 1.8.8 groups get their own proxy:

```text
players (1.8 - current) ──> main Velocity   (player-info-forwarding-mode = "modern")      ──> Paper 26.x groups
1.8 players             ──> legacy Velocity (player-info-forwarding-mode = "bungeeguard") ──> PandaSpigot 1.8.8 groups
```

- The main proxy stays on `modern`. Nothing about the legacy groups weakens forwarding for the modern ones.
- The legacy proxy runs in `bungeeguard` mode. Velocity sends the legacy BungeeCord forwarding data and adds a `bungeeguard-token` property whose value is the legacy proxy's `forwarding-secret`. The two proxies use different secrets.
- Backends set `bungeecord: true` in `spigot.yml` and `online-mode=false`. `proxyOnlineMode` in `pandaspigot.yml` must match the legacy proxy's `online-mode`.
- The legacy proxy and its backends are separate entry points. A 1.8 client cannot be transferred between proxies in-session (the transfer packet needs 1.20.5+), so how players reach the legacy proxy is a routing decision for a later stage.

## BungeeGuard token check on PandaSpigot

PandaSpigot does **not** check the token itself. Its configuration (`pandaspigot.yml`, `paper.yml`, `spigot.yml` of build 226) has no forwarding-secret or token option. Without a check, anyone who can reach a backend port can log in with any name and UUID.

What it does have is a backport of Paper's `PlayerHandshakeEvent`, under Paper's original class name `com.destroystokyo.paper.event.player.PlayerHandshakeEvent` ([patch 0004](https://github.com/hpfxd/PandaSpigot/blob/master/patches/api/0004-Backport-PlayerHandshakeEvent-from-Paper.patch)). The patch description names BungeeGuard as the reason for the backport.

Options for checking the token:

1. **BungeeGuard backend plugin** ([lucko/BungeeGuard](https://github.com/lucko/BungeeGuard), MIT, latest release v1.4.0 from 2025-06-02). It picks `PaperHandshakeListener` when `PlayerHandshakeEvent` exists and falls back to ProtocolLib otherwise ([`BungeeGuardBackendPlugin`](https://github.com/lucko/BungeeGuard/blob/master/bungeeguard-spigot/src/main/java/me/lucko/bungeeguard/spigot/BungeeGuardBackendPlugin.java)). Because PandaSpigot keeps the Paper class name, the Paper path should be used. Not installed or tested in this spike.
2. **Own bridge plugin.** Listen to `PlayerHandshakeEvent`, parse the forwarded handshake, compare `bungeeguard-token` against the configured secrets in constant time, call `setFailed(true)` on a mismatch and `setServerHostname(...)` otherwise. This is the same approach as option 1 without a third-party jar and lets the Vectora config hand out the secret.

Option 2 is implemented in `cloud-bridge-legacy` (stage 2, PR 13), without a third-party plugin: see [Bridges](bridges.md#legacy-bridge-bungeeguard-check). It also refuses every login whose handshake it has not accepted, which covers connections that arrive before the plugin is enabled.

Either way, backends should also listen only on the internal network or behind a firewall. The token check is a second layer.

## Floodgate on 1.8.8

Floodgate's documentation does not list 1.8.8 as supported, and it does not exclude it either. From the source and the issue tracker:

- Floodgate-Spigot is compiled against the Spigot 1.19.4 API ([`Versions.kt`](https://github.com/GeyserMC/Floodgate/blob/master/build-logic/src/main/kotlin/Versions.kt)) but resolves server internals by reflection, including the `v1_8_R3` package layout ([`ClassNames.java`](https://github.com/GeyserMC/Floodgate/blob/master/spigot/src/main/java/org/geysermc/floodgate/util/ClassNames.java)).
- The maintainers fixed 1.8.8 breakages in [#198](https://github.com/GeyserMC/Floodgate/issues/198) (2021) and [#500](https://github.com/GeyserMC/Floodgate/issues/500) (2024). Their stance in #198: "support Floodgate on 1.8 as long as we can but this could change at any time". In [#219](https://github.com/GeyserMC/Floodgate/issues/219) they said they are not interested in working around forks.

Conclusion: best effort, not a supported target. If Bedrock access to 1.8.8 groups is needed, Floodgate belongs on the legacy proxy (Velocity is a supported platform), and Floodgate on the PandaSpigot backends is optional and must be tested per release. Geyser also speaks the current Java protocol, so the 1.8.8 backends would need protocol translation (ViaVersion family) in between. That is not covered here.

## Risks

- **Small maintainer team.** The repository ([hpfxd/PandaSpigot](https://github.com/hpfxd/PandaSpigot), GPL-3.0, 418 stars) belongs to one person (hpfxd, 73 commits in total, none among the last 30). The recent work comes from a few volunteers, mainly anzz1 and uRyanxD (12 and 11 of the last 30 commits). 53 open issues, last push 2026-09-29. If they stop, there is no fallback with the same feature set.
- **Old codebase.** Minecraft 1.8.8 is from 2015 and gets no fixes from Mojang. Security and exploit fixes depend entirely on the fork.
- **Java beyond 25.** See the `sun.misc.Unsafe` warning above. The weekly workflow tests Java 21 only; a run on a newer JDK should be added before switching legacy groups to it.
- **Build list order.** The download API returns the build numbers unsorted. The workflow picks the highest number, not the last element.
- **Startup downloads the vanilla jar.** Paperclip fetches the Mojang 1.8.8 server jar on first start and patches it. Hosts need outbound access to Mojang on first run, or a prepared cache.

## Licence and EULA

PandaSpigot is GPL-3.0, like this project. Vectora does not redistribute the jar; the cloud and the workflow download it from the official API and check the sha256.

Starting the server requires accepting the Minecraft EULA. The workflow writes `eula=true` only into its throwaway workspace for the smoke test. Operators accept the EULA for their own servers.
