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
| `VECTORA_SERVICE_PLATFORM` | Velocity bridge | `cloud-node`, for every service | Name of cloud-template's `ServerPlatform`. `VELOCITY_LEGACY` makes the bridge the legacy proxy, anything else (also a missing value) the main proxy. The bridge logs the role it took; for a value other than `VELOCITY` or `VELOCITY_LEGACY` it logs a warning (without repeating the value). The Paper and legacy bridges log their role on enable as well. |
| `VECTORA_LEGACY_FORWARDING_SECRET` | legacy bridge | `cloud-node`, for `PANDASPIGOT` services only | The forwarding secret of the legacy proxy, the same value as in `Legacy-Proxy/runtime/forwarding.secret`. Must follow the template's secret rule (16 or more characters from `A-Z a-z 0-9 . _ ~ + / = -`). Read from the environment only, never from a system property, which would show on the command line. |
| `VECTORA_FALLBACK_GROUP` | Velocity bridge | `cloud-node`, for both proxies | The group a kicked player goes to when the own group has no free server: `fallback-group` of `cloud.yml`, default `lobby`. Read once when the bridge connects, so a change reaches a proxy with its next start. A missing value (a proxy started by an older node) or one that is no valid group name gives `lobby`; an invalid one is logged as a warning, without repeating it. |

All names are constants in `net.vectora.protocol.ServiceEnvironment` (`SERVICE_PLATFORM` and `LEGACY_FORWARDING_SECRET` added with this module, `FALLBACK_GROUP` later), so node and bridges cannot drift apart. `cloud-node` passes the services an environment without the cloud's own `VECTORA_*` variables (see [node.md](node.md#process)), so the node adds the last three explicitly: the platform for every service, the legacy secret only for `PANDASPIGOT`, the fallback group only for the two proxies.

## Threads

`CloudClient` writes to a blocking socket. A slow or stuck cloud must never hold up the Paper main thread or a Velocity event thread, so no bridge calls the client from a platform thread:

- Every call goes through `BridgeSender`: a bounded queue (1024 actions) and one daemon thread, `vectora-bridge-sender`, which makes the actual calls. Submitting never blocks. A full queue drops the action and counts it, with a warning for the first drop and every thousandth.
- Status reports are coalesced. However many are waiting, only the newest is sent and only one queue slot is used.
- Even the connect (`CloudClient.start`) is queued. `onEnable` only creates the client and the sender.
- The one wait on a platform thread is at shutdown: `BridgeSender.close()` lets the queue drain, closes the client (which sends `Disconnect(SHUTDOWN)`) and waits at most 3 seconds. If the sender thread still hangs in a write after that (a cloud that stopped reading), the client is closed from the closing thread. `CloudClient.close()` gives the goodbye 500 ms and then closes the socket, which also fails the hung write, so neither close blocks on a full socket.
- Messages from the cloud (`RegisterServer`, `UnregisterServer`, `ServerCapacity`, `TransferPlayer`) arrive on the client's event thread. The Velocity bridge changes the server list there; Velocity's server list is thread-safe. A host name in `RegisterServer` (the cloud sends IP literals) would be resolved on that thread, not on a proxy thread.

## Velocity bridge

| Proxy event | Bridge |
|---|---|
| `ListenerBoundEvent` (Minecraft listener) | The first time: reads the environment, opens the connection, schedules the status every 5 seconds. Every time: `markReady`, players can connect now. |
| `PlayerChooseInitialServerEvent` | Replaces the proxy's first server with the emptiest server of the same group (below). |
| `KickedFromServerEvent` | Every kick except a ban, the whitelist, the vanilla `/kick` and a kick marked by our moderation: the player goes to another server of the group, else to the lobby, else is disconnected with the original reason ([below](#catching-kicks)). After a kick with its own words the player reads them as a chat line once moved. A ban, the whitelist, the vanilla `/kick` and a marked kick stay as Velocity decided; only a redirect into a group goes to the emptiest server of that group, never back to the one that kicked, and counts for the kick guard like a caught kick. |
| `ServerPreConnectEvent` | Refuses a switch to a server the cloud closed (`ServerCapacity open=false`) with a short line, unless the player has `vectora.server.bypass-closed` ([below](#closed-servers)). |
| `ServerConnectedEvent` | `PlayerJoin` on the first backend, `PlayerSwitch` afterwards. |
| `DisconnectEvent` | `PlayerQuit`, if a join was reported. |
| `ProxyShutdownEvent` (registered once connected) | Stops the status task, closes the sender. |

### Dynamic servers

`ServerRegistry` applies `RegisterServer` and `UnregisterServer` to the proxy's server list. Only the cloud can send them, the registry still guards the proxy against a confused cloud:

- A server whose `legacy` flag does not fit the proxy is refused. The main proxy never gets a 1.8.8 backend (whose forwarding is weaker), the legacy proxy never a current one.
- Servers from `velocity.toml` are never replaced; a `RegisterServer` for one that is listed changes nothing (`CONFIGURED`, no warning). An `UnregisterServer` for one withdraws it from the running server list (`WITHDRAWN`): the cloud stopped that server, for example because its group was switched off on a reload, so the proxy no longer offers it. `velocity.toml` keeps the line until the proxy restarts, and the cloud renders it without the server then. A later `RegisterServer` adds it again under the same name. A `velocity.toml` server that someone else took out is not put back. A name that another plugin registered is left alone.
- The same address again changes nothing; another address replaces the entry.
- Apart from withdrawn `velocity.toml` servers, only servers the registry added are removed again. A lost connection to the cloud leaves the list as it is, since the servers keep running.

### Choosing the server and moving players

The node sends `ServerCapacity` for every backend it registers: its slots (`players-per-service` of its task) and whether it is open. `ServerLoads` keeps that per backend together with its group, and picks for a group the open, listed backend with the lowest load in percent (players / slots); of equal ones the one with fewer players, then by name. A player on the way to a backend counts there for 10 seconds or until the connect is reported, so a burst of joins is spread. A full backend (players, those on their way included, at or above its slots) is skipped; if every backend of the group is full, there is no choice and the proxy's own one stays (on join) or the next step of the kick handling follows. A backend without slots (registered by a 1.0 node) is not chosen; the proxy's own choice stays.

This decides the first server on join (Velocity's `try` list still picks the group), the target of a caught kick and a redirect into a group after a kick that is not caught, on the main proxy and on the legacy proxy alike.

#### Catching kicks

When a backend kicks a player or refuses the connect (`KickedFromServerEvent`, also `kickedDuringServerConnect`), the adapter flattens the reason (`KickReasons`: translation keys and text, arguments and children included) and `ProxyBridge.kicked` decides:

1. `KickClassifier` decides whether the kick is caught, in this order:
   - Never: the moderation marker (below); the vanilla `/kick` without a reason of its own, that is the key `multiplayer.disconnect.kicked` (Paper and Spigot since 1.13) or the whole text `Kicked by an operator.` (1.8.8, Spigot and PandaSpigot; case, colour codes and a closing `.` or `!` ignored), because a kick by an operator is a punishment like a ban; a key starting with `multiplayer.disconnect.banned` or `multiplayer.disconnect.not_whitelisted`, or one of the words `ban`, `bans`, `banned`, `bann`, `gebannt`, `verbannt`, `verbannen`, `gesperrt`, `tempban`, `tempbanned`, `permban`, `permbanned`, `ipban`, `ipbanned`, `banished`, `whitelist`, `whitelisted`, `white-listed` in the text. Velocity then does what it would do without the bridge.
   - Caught with the bridge's notice (the server went away): no reason at all (the connection was lost) or an empty one; the keys `multiplayer.disconnect.server_shutdown`, `multiplayer.disconnect.server_full`, `disconnect.timeout`, `disconnect.genericReason`, `disconnect.closed`, `disconnect.lost`, `disconnect.endOfStream`, `disconnect.overflow`; the texts `Server closed`, `Server is restarting`, `Server restarting`, `Timed out`, `The server is full`, `Server is full` (case, colour codes and a closing `.` or `!` ignored) and any text starting with `Internal Exception:`.
   - Caught with the original reason: anything else, a plugin's or an admin's own words as well as vanilla keys such as `multiplayer.disconnect.idling`. A `/kick <player> <reason>` arrives as the bare reason without a key and therefore counts as own words: it is caught. The player is moved like after a stop and then reads the backend's reason, unchanged, as a chat line (Velocity sends the redirect message once the move succeeded).
2. A caught kick sends the player to the emptiest open server of the group of the server that kicked, never to that server. Without one, to the emptiest open server of the fallback group: `fallback-group` in `cloud.yml` (console `config set fallback-group <group>`, API `PUT /config`), default `lobby` (`ProxyBridge.DEFAULT_FALLBACK_GROUP`). The node passes it to both proxies in `VECTORA_FALLBACK_GROUP` when it starts them, so a change takes effect with the proxy's next start. The legacy proxy only finds 1.8.8 groups; with a fallback group that is not behind it, it disconnects as without a free server.
3. A player who still is on another server (a refused switch) only moves inside the group; without a free server there they stay where they are.
4. Otherwise the player is disconnected with the backend's reason; without one, with a short text in their client language.
5. `KickGuard` prevents loops: within one incident every server is tried once (the servers a player was kicked from and sent to stay excluded until 60 seconds after the last caught kick), and at most 3 kicks per player are caught within 60 seconds. The fourth one disconnects with the original reason. Leaving the proxy ends both.
6. A kick that is not caught but that Velocity turns into a redirect (its own fallback through the `try` list) goes through the same guard (`ProxyBridge.redirect`): it counts towards the 3 per 60 seconds, the target is the emptiest server of the redirect's group that was not tried in the incident, and the servers it involves are excluded from then on. If Velocity's target itself was tried already and the group has no other free server, or the limit is reached, the player is disconnected with the original reason instead of bouncing between `lobby-1` and `lobby-2`. A target outside the cloud's groups stays, but is counted.

After a stop, crash or full server the player reads a short line in their client language (`TransferMessages.rescued`) as Velocity's redirect message; after any other caught kick the backend's own reason instead.

**Moderation marker.** A kick by VectoraCloud's own moderation is never caught: the player is meant to leave the network. The module marks it with the reserved translation key `vectora.moderation` (`KickClassifier.MODERATION_KEY`) or a key below it (`vectora.moderation.kick`), anywhere in the reason component: as the component itself, a child or an argument. So that the player still reads the reason, the module uses the fallback text `%s` with the visible reason as argument, in Adventure `Component.translatable().key("vectora.moderation.kick").fallback("%s").arguments(reason)`; clients without a translation for the key show the fallback. Other plugins must not use keys below `vectora.moderation`. A plain-text kick (`kickPlayer(String)`, for example on 1.8.8) cannot carry the marker; such a kick is caught.

#### Closed servers

A server the node closed with `ServerCapacity open=false` is being emptied before it stops. `ProxyBridge.mayConnect` refuses every connect to it (`ServerPreConnectEvent`: `/server`, another plugin's connect, a redirect) and the player reads a short line in their client language (`TransferMessages.closed`). Let through are players with the permission `vectora.server.bypass-closed` (`ProxyBridge.BYPASS_CLOSED_PERMISSION`) and the first server of a login: Velocity leaves a player without any server hanging when that connect is refused, and the choice of the first server already skips closed servers when the group has an open one. A server whose slots the proxy does not know (registered by a 1.0 node) is never treated as closed.

`TransferPlayer` moves one player, for a scale-down of the backend they are on. `ProxyBridge` carries it out only if the player is on a backend of a known group, and the target is a different backend of that same group that is listed and open; anything else is ignored with a warning. The player first gets a short message in their client language (`Player.getEffectiveLocale`: German for `de`, English otherwise, `TransferMessages`) and is then connected with `connectWithIndication`. If the connect fails, the player stays where they are; the node then calls the scale-down off. See [scaling.md](scaling.md#where-players-go).

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

Players online and the shown player limit. A proxy has no game loop and reports 20 TPS and 0 MSPT. The players a backend reports are what the [autoscaler](scaling.md#signals) scales on, together with the players the proxy reports on it.

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
| `ProxyBridgeTest`, `ServerRegistryTest`, `PlayerTrackerTest` | Server list per role (counter-check: each proxy takes its own kind), configured servers never replaced, withdrawn on the cloud's `UnregisterServer` and added again later, foreign servers left alone; joins and redirects to the emptiest server of the group; redirects counted by the kick guard, tried servers skipped, no ping-pong between two lobbies, a shared limit with caught kicks (counter-check: another player, leaving the proxy); transfers only inside the group to a listed, open, different server; a caught kick to the group, then the fallback group (`lobby` or the configured one, counter-check: the default bridge), the fallback group from the environment (missing, blank and invalid values give `lobby`), each server once per incident, the fourth within 60 s and no free server disconnecting, bans, the whitelist, the vanilla `/kick` (key and 1.8.8 text) and kicks with the moderation marker left alone, a kick with unknown words or a vanilla idle kick caught with the original reason shown and a stop with the notice, a player on another server moved only inside the group, a closed server refused except with the bypass, on the first server of a login and without known slots (each with the counter-check); consistent join, switch and quit, ignored repeats, unknown servers and invalid names, out-of-order events per session, Bedrock detection. |
| `ServerLoadsTest` | Lowest load in percent, then fewer players, then name; pending players counted for 10 seconds; closed, unlisted and slot-less servers skipped; a full server skipped, players on their way included (counter-check: one slot left, a player leaves); the excluded server; only a server with slots and `open=false` counts as closed; the German and English transfer, rescue, disconnect and closed messages by locale. |
| `KickClassifierTest`, `KickGuardTest`, `KickReasonsTest` (velocity) | Every refused key and text (ban with its German, temporary, permanent and IP variants, whitelist, vanilla `/kick` as key and as 1.8.8 text with the counter-check of longer texts, moderation marker, counter-check: keys that only look like the marker), the keys and texts of a server that went away, unknown keys and texts caught with the reason, a ban or the marker anywhere in the reason winning over a shutdown, a missing or empty reason; servers tried once per incident and the incident ending 60 s after the last caught kick (59 s and 60 s), 3 kicks within 60 s and the fourth refused, leaving the proxy resetting; keys and texts collected from children and arguments, the marker found as the moderation module sets it (counter-check: the same words without it are caught). `KickReasonsTest` loads only Adventure and runs on Java 21. |
| `BungeeGuardCheckTest`, `PropertiesJsonTest`, `AdmissionTicketsTest` | Right, wrong, empty and missing token, two tokens, the main proxy's secret, direct connection, host name as address, no secret; the token as Velocity's Gson writes it (`=` as `=`); the stored fuzz inputs reach the outcome their name says; token stripped; escapes and round trip; every malformed form; tickets used once and expiring. |
| `HandshakeGuardTest`, `LoginGateTest` (legacy) | The guard on the real PandaSpigot events: right, wrong and missing token, direct connection, another plugin un-failing or cancelling, missing and invalid secret with the counter-check, log limit; logins with and without ticket, another plugin allowing a refused login (with the counter-check). |
| `ForwardedHandshakeFuzzTest` | Jazzer: any handshake gives a result and never an exception, an accepted one never carries a token on; any properties JSON fails only with `MalformedHandshakeException` and what parses round-trips. |
| `VelocityServersTest`, `VelocityBridgePluginTest`, `PluginDescriptorTest` | The Velocity adapter on a scripted `ProxyServer`; plugin descriptors, Guice constructor and event handlers. Tagged `requires-java25` where they load Velocity or Paper classes. |

All tests of `cloud-bridge-common` and `cloud-bridge-legacy` run on Java 25 and, with `-PtestJava=21`, on Java 21.

## Open for later pull requests

- The node copies the bridge jar into the plugin folder of each service before it starts ([node.md](node.md#server-software)); the three jars are embedded in `cloud-node` with their SHA-256. A PandaSpigot service does not start without the legacy bridge.
- PR 24 (legacy proxy group): a real start of the legacy proxy with a PandaSpigot backend and the token check end to end.
- Stage 3: the Vectora plugin per platform takes over `cloud-bridge-common`.
