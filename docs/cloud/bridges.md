# Bridges

The bridges connect the proxies and servers that VectoraCloud starts to the [cloud channel](network.md). They are interim plugins for stage 2. From stage 3 on, the one Vectora plugin per platform takes over what they do, so the logic sits in a platform-neutral module and the platform modules are thin adapters.

| Module | Jar | Runs in | Does |
|---|---|---|---|
| `cloud-bridge-common` | (bundled into the three below) | | Send queue, server registry and player state of the proxy, tick meter, BungeeGuard check. No platform API. |
| `cloud-bridge-velocity` | `VectoraCloudBridgeVelocity-<version>.jar` | Velocity 4.3, main proxy and legacy proxy (Java 25) | Adds and removes dynamic servers, reports player events and status, ready signal. |
| `cloud-bridge-paper` | `VectoraCloudBridgePaper-<version>.jar` | Paper 26.3 (Java 25) | Ready signal and status. |
| `cloud-bridge-legacy` | `VectoraCloudBridgeLegacy-<version>.jar` | PandaSpigot 1.8.8 (Java 21) | Ready signal and status, plus the check of the legacy proxy's `bungeeguard-token`. |

Each jar contains the bridge core, `Vectora-CloudClient` and `Vectora-Protocol`, all Vectora code. No third-party library is bundled, and none is needed at runtime besides the platform itself.

## Environment

| Variable | Read by | Set by | Content |
|---|---|---|---|
| `VECTORA_CLOUD_HOST`, `VECTORA_CLOUD_PORT`, `VECTORA_SERVICE_ID`, `VECTORA_SERVICE_TOKEN` | all bridges | `cloud-node` | See [network.md](network.md#environment-variables). Missing id or token: the bridge logs why and stays inactive (the legacy token check still runs). |
| `VECTORA_SERVICE_PLATFORM` | Velocity bridge | `cloud-node`, for every service | Name of cloud-template's `ServerPlatform`. `VELOCITY_LEGACY` makes the bridge the legacy proxy, anything else (also a missing value) the main proxy. |
| `VECTORA_LEGACY_FORWARDING_SECRET` | legacy bridge | `cloud-node`, for `PANDASPIGOT` services only | The forwarding secret of the legacy proxy, the same value as in `Legacy-Proxy/runtime/forwarding.secret`. Must follow the template's secret rule (16 or more characters from `A-Z a-z 0-9 . _ ~ + / = -`). Read from the environment only, never from a system property, which would show on the command line. |

All names are constants in `net.vectora.protocol.ServiceEnvironment` (`SERVICE_PLATFORM` and `LEGACY_FORWARDING_SECRET` added with this module), so node and bridges cannot drift apart. `cloud-node` passes the services an environment without the cloud's own `VECTORA_*` variables (see [node.md](node.md#process)), so the node adds the last two explicitly: the platform for every service, the legacy secret only for `PANDASPIGOT`.

## Threads

`CloudClient` writes to a blocking socket. A slow or stuck cloud must never hold up the Paper main thread or a Velocity event thread, so no bridge calls the client from a platform thread:

- Every call goes through `BridgeSender`: a bounded queue (1024 actions) and one daemon thread, `vectora-bridge-sender`, which makes the actual calls. Submitting never blocks. A full queue drops the action and counts it, with a warning for the first drop and every thousandth.
- Status reports are coalesced. However many are waiting, only the newest is sent and only one queue slot is used.
- Even the connect (`CloudClient.start`) is queued. `onEnable` only creates the client and the sender.
- The one wait on a platform thread is at shutdown: `BridgeSender.close()` lets the queue drain, closes the client (which sends `Disconnect(SHUTDOWN)`) and waits at most 3 seconds.
- Messages from the cloud (`RegisterServer`, `UnregisterServer`) arrive on the client's event thread. The Velocity bridge changes the server list there; Velocity's server list is thread-safe. A host name in `RegisterServer` (the cloud sends IP literals) would be resolved on that thread, not on a proxy thread.

## Velocity bridge

| Proxy event | Bridge |
|---|---|
| `ListenerBoundEvent` (Minecraft listener) | The first time: reads the environment, opens the connection, schedules the status every 5 seconds. Every time: `markReady`, players can connect now. |
| `ServerConnectedEvent` | `PlayerJoin` on the first backend, `PlayerSwitch` afterwards. |
| `DisconnectEvent` | `PlayerQuit`, if a join was reported. |
| `ProxyShutdownEvent` (registered once connected) | Stops the status task, closes the sender. |

### Dynamic servers

`ServerRegistry` applies `RegisterServer` and `UnregisterServer` to the proxy's server list. Only the cloud can send them, the registry still guards the proxy against a confused cloud:

- A server whose `legacy` flag does not fit the proxy is refused. The main proxy never gets a 1.8.8 backend (whose forwarding is weaker), the legacy proxy never a current one.
- Servers from `velocity.toml` are never replaced; a `RegisterServer` for one that is listed changes nothing (`CONFIGURED`, no warning). An `UnregisterServer` for one withdraws it from the running server list (`WITHDRAWN`): the cloud stopped that server, for example because its group was switched off on a reload, so the proxy no longer offers it. `velocity.toml` keeps the line until the proxy restarts, and the cloud renders it without the server then. A later `RegisterServer` adds it again under the same name. A `velocity.toml` server that someone else took out is not put back. A name that another plugin registered is left alone.
- The same address again changes nothing; another address replaces the entry.
- Apart from withdrawn `velocity.toml` servers, only servers the registry added are removed again. A lost connection to the cloud leaves the list as it is, since the servers keep running.

### Player events

The proxy's events are not passed on as they come. `PlayerTracker` checks them against what it has already reported, so the cloud receives a consistent sequence per player: one `PlayerJoin`, any number of `PlayerSwitch` whose `from` is the last reported service, one `PlayerQuit`.

- A player counts as joined once the first backend accepted them. Leaving before that produces nothing.
- The "previous server" of the platform event is not trusted: a switch is reported from the service the tracker reported last.
- A connect to the current server again, a second disconnect, a disconnect of an unknown player, a server the proxy does not know, a server name that is not a valid service id and a player name the protocol does not allow change nothing and produce nothing.
- The name of the join is kept for the later events of that player.
- State is kept per session, that is per Velocity player object, because Velocity fires the connect and disconnect events of one player on different threads. A connect that arrives after the disconnect of its session is ignored (ended sessions are remembered weakly). An old session's late disconnect leaves a newer login with the same UUID alone. A newer session that connects while the old one is still recorded ends it first: `PlayerQuit`, then `PlayerJoin`.
- The events are queued while the tracker's lock is held, so two threads cannot swap them on the way to the send queue.
- Bedrock players are recognised by Floodgate's UUID scheme (upper 64 bits zero), without the Floodgate API.

Backend names: a dynamic server is registered under its service id. A static backend appears in `velocity.toml` under its group name (`lobby`) when its index is 1 (see [node.md](node.md#ids-folders-and-ports)), so the proxy reports `lobby`, not `lobby-1`. `cloud-node` maps that name back to the service when it takes the events (`NetworkPlayers`, see [node.md](node.md#life-cycle)).

### Status

Players online and the shown player limit. A proxy has no game loop and reports 20 TPS and 0 MSPT.

## Paper bridge

- Ready: a task for the first server tick, which runs after "Done".
- Status: every 100 ticks with `Bukkit.getTPS()[0]` and `Bukkit.getAverageTickTime()`, and at once when a player joins or leaves. The player events of the network come from the proxy; a backend sends none, so the cloud sees each of them once.

## Legacy bridge: BungeeGuard check

PandaSpigot accepts the BungeeCord handshake of the legacy proxy (`bungeecord: true`) but does not check the `bungeeguard-token` in it ([Legacy 1.8.8 groups](legacy-1.8.8.md#bungeeguard-token-check-on-pandaspigot)). Without the check, anyone who reaches a backend port could log in under any name and UUID. The legacy bridge does the check itself, without a third-party plugin.

The handshake field then reads `host \0 player-ip \0 uuid-without-dashes \0 properties-json`. The parts are checked strictly (`ForwardedHandshake`):

| Part | Rule |
|---|---|
| Whole field | at most 32767 characters, three or four parts |
| Host | 1 to 255 characters without control characters |
| Player address | an IPv4 or IPv6 literal (with an optional zone), never a host name: the server turns it into an `InetSocketAddress`, and a host name would make it resolve DNS during the login |
| UUID | exactly 32 hex digits |
| Properties | a JSON array of at most 32 objects with the string members `name`, `value` and optionally `signature` (or `null`). Nothing else: no other members, numbers, nesting, duplicate members, raw control characters or trailing data. Own small parser (`PropertiesJson`), no JSON library on the server's class path. |

`BungeeGuardCheck` then decides:

| Handshake | Result |
|---|---|
| exactly one `bungeeguard-token`, equal to the secret | accepted; the token is removed from the properties |
| no token (also plain BungeeCord forwarding without properties) | refused, `MISSING_TOKEN` |
| a wrong token, or more than one | refused, `WRONG_TOKEN` |
| not a forwarded handshake (a direct connection) or a broken part | refused, `MALFORMED` |
| no valid secret configured | every handshake refused, `NO_SECRET` |

The comparison runs in constant time: `ForwardingSecret` keeps only the SHA-256 digest of the secret and compares digests with `MessageDigest.isEqual`, so the time depends neither on where a candidate differs nor on its length. The secret never appears in `toString()`, in a log line or in an error message.

How it maps onto PandaSpigot's backported `PlayerHandshakeEvent` (`HandshakeGuard`):

- At `LOWEST` the guard checks `getOriginalHandshake()` and un-cancels the event; PandaSpigot only takes the event's values (and only honours `setFailed`) if it is not cancelled. Accepted: forwarded host, player address, UUID and the properties without the token. Refused: `setFailed(true)` and a message that says nothing about the reason.
- At `MONITOR` it applies the same decision again. Another plugin can therefore neither let a refused handshake through nor cancel the event, which would make PandaSpigot fall back to its own unchecked parsing and hand the token to the game profile, where other players could see it.
- Every accepted handshake leaves a ticket for its UUID (valid once, for one minute). `PlayerLoginEvent` at `LOWEST` refuses every login without a ticket, and `MONITOR` refuses it again, so no other plugin can let it through with `allow()`. That covers a handshake that reached the server before the plugin was enabled (the plugin loads at `STARTUP`) or that another plugin handled.
- Refusals are logged with the client address and the reason, at most 10 lines a minute; the dropped count follows with the next line.

The check is a second layer. The backends still bind to a loopback or private address (`VECTORA_SERVER_HOST`) and should not be reachable from outside.

### Status on 1.8.8

1.8.8 has no TPS API. `TickMeter` takes `ServerTickEndEvent` (part of PandaSpigot's API): MSPT is the average tick duration over the last 100 ticks, TPS the number of those ticks divided by the time they took, at most 20.

## Compiling against PandaSpigot

`cloud-bridge-legacy` compiles against the real API, `com.hpfxd.pandaspigot:pandaspigot-api`, from the project's own repository `https://repo.hpfxd.com/releases/` (documented in the [PandaSpigot README](https://github.com/hpfxd/PandaSpigot)). Licence GPL-3.0, like Vectora. Only a snapshot is published, so the build pins one unique snapshot (`1.8.8-R0.1-20251003.170525-12`) instead of the moving `-SNAPSHOT`; a new upload cannot change the build without a commit.

| Choice | Why |
|---|---|
| Real API instead of hand-written stubs | Stubs would have to repeat the exact signatures (for example `Bukkit.getOnlinePlayers()` returns a `Collection` on 1.8.8, an array in older builds), and a mismatch would only show at runtime on the server. With the real jar javac checks every call. The tests also run against the real `PlayerHandshakeEvent` and `PlayerLoginEvent`. |
| `compileOnly`, not transitive | The server provides the API at runtime. The libraries it declares (Guava, Gson, ebean, ...) are not needed for compiling, except `adventure-api` in the declared version 4.24.0: Bukkit's `Player` and event classes use Adventure types in overloads, and javac has to see them. |
| Repository limited to one group | `settings.gradle.kts` declares the repository as `exclusiveContent` for `com.hpfxd.pandaspigot`. Nothing else is fetched from it, and that group is fetched from nowhere else. |
| Tests transitive | The tests load Bukkit classes, which need `commons-lang` and `bungeecord-chat` at runtime. Test scope only. |

The API has Java 8 class files, so the module needs no `disableAutoTargetJvm()`, and all of its tests run on Java 21 as well.

## Tests

| Test | Covers |
|---|---|
| `BridgeSenderTest` | Calls run on `vectora-bridge-sender`, never on the calling thread, which is a daemon thread; submitting returns at once while the link hangs; full queue drops and counts; coalescing of 50 reports into one; a report after a full queue still goes out; a failing call does not stop the sender; close drains, closes the link, refuses more and waits only as long as allowed. |
| `ProxyBridgeTest`, `ServerRegistryTest`, `PlayerTrackerTest` | Server list per role (counter-check: each proxy takes its own kind), configured servers never replaced, withdrawn on the cloud's `UnregisterServer` and added again later, foreign servers left alone; consistent join, switch and quit, ignored repeats, unknown servers and invalid names, out-of-order events per session, Bedrock detection. |
| `BungeeGuardCheckTest`, `PropertiesJsonTest`, `AdmissionTicketsTest` | Right, wrong, empty and missing token, two tokens, the main proxy's secret, direct connection, host name as address, no secret; the token as Velocity's Gson writes it (`=` as `=`); the stored fuzz inputs reach the outcome their name says; token stripped; escapes and round trip; every malformed form; tickets used once and expiring. |
| `HandshakeGuardTest`, `LoginGateTest` (legacy) | The guard on the real PandaSpigot events: right, wrong and missing token, direct connection, another plugin un-failing or cancelling, missing and invalid secret with the counter-check, log limit; logins with and without ticket, another plugin allowing a refused login (with the counter-check). |
| `ForwardedHandshakeFuzzTest` | Jazzer: any handshake gives a result and never an exception, an accepted one never carries a token on; any properties JSON fails only with `MalformedHandshakeException` and what parses round-trips. |
| `VelocityServersTest`, `VelocityBridgePluginTest`, `PluginDescriptorTest` | The Velocity adapter on a scripted `ProxyServer`; plugin descriptors, Guice constructor and event handlers. Tagged `requires-java25` where they load Velocity or Paper classes. |

All tests of `cloud-bridge-common` and `cloud-bridge-legacy` run on Java 25 and, with `-PtestJava=21`, on Java 21.

## Open for later pull requests

- PR 14 (downloads): copy the bridge jar into the plugin folder of each service. Environment, `RegisterServer` with the `legacy` flag to the proxy of the right kind, and the player events with static backend names are in `cloud-node` since stage 2 (see [node.md](node.md#life-cycle)).
- PR 24 (legacy proxy group): a real start of the legacy proxy with a PandaSpigot backend and the token check end to end.
- Stage 3: the Vectora plugin per platform takes over `cloud-bridge-common`.
