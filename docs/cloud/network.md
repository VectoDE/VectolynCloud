# Cloud channel: network and client

Every proxy and server that VectoraCloud starts keeps one TCP connection to its node. Over it the service reports that it is ready, sends its status and player events, and exchanges channel messages and requests with the cloud and with other services. The wire format is described in [protocol.md](protocol.md). This page covers the two ends:

| Module | Package | Side |
|---|---|---|
| `cloud/network` | `net.vectora.cloud.network` | Netty server inside the cloud (`CloudNetworkServer`) |
| `common/client` | `net.vectora.cloud.client` | Client inside every proxy and server (`CloudClient`) |

## Security model

The channel carries no secrets beyond the service tokens, but a peer that gets in could report false status, send fake player events or call request handlers. The defaults are therefore strict.

| Measure | Default | Where it is set |
|---|---|---|
| Listen address | `127.0.0.1:7010`, reachable from this machine only | `host` and `port` of this node in `nodes.yml` |
| Authentication | A random token per service and start (32 bytes from `SecureRandom`, 43 characters). The cloud compares it in constant time (`AuthToken.matches`). An unknown service id is compared against a decoy token, so it takes as long as a known one. | `ServiceTokens` |
| Token handling | Never logged. `AuthToken`, `Hello`, `SessionTicket` and `CloudClientSettings` print `[redacted]` instead. The token reaches the service process in an environment variable, not on the command line (set by `cloud/node`, PR 8). | |
| One connection per token | The token in the environment opens one connection. On `ACCEPTED` the cloud uses it up and sends a `SessionTicket` over that connection: the token for the next `Hello`, again for one connection. Check and replacement are one step (compare and swap), so of two connections with the same token only one gets in. A copy of the token from the environment, or a ticket that was used once, gets `INVALID_TOKEN`. | `ServiceTokens.admit` |
| Frame size before authentication | 4 KiB (`Protocol.MAX_HANDSHAKE_FRAME_SIZE`). A larger length prefix ends the connection as soon as it has arrived, without waiting for the body. After `AuthResult(ACCEPTED)`: 1 MiB. | both ends |
| Handshake timeout | 10 s from the accept. A peer that sends `Hello` one byte at a time (slowloris) is cut off all the same. | `NetworkSettings` |
| Idle timeout | 30 s without any frame from an authenticated service. The cloud sends `Ping` after 10 s without output, the client every 10 s. | `NetworkSettings`, `CloudClientSettings` |
| Connection limits | 1024 open connections in total, 256 per address, counted from the accept (authenticated or not). An address is one IPv4 address or one /64 of IPv6 (one host usually gets a whole /64). An IPv4-mapped address (`::ffff:a.b.c.d`) and one of the NAT64 prefix `64:ff9b::/96` count as the IPv4 address they carry, so the IPv4 hosts behind a NAT64 gateway do not share one limit. On a single node every service connects from the loopback address, so the per-address limit is high. A refused connection is closed at once; the log gets a warning for the first and then every 1000th. | `NetworkSettings`, `AddressKey` |
| Limit for unauthenticated connections | 128 in total and 32 per address that have not been accepted yet. A connection leaves this count when it is accepted, and gives its slot back when it closes (refused, timed out or ended). A peer that opens connections and never says `Hello` therefore cannot use up the slots the services need. | `NetworkSettings` |
| Open requests | 256 per connection and direction. Beyond that the cloud answers `DENIED`. | `NetworkSettings` |
| Protocol violations | `Disconnect(PROTOCOL_ERROR)` and close. That covers undecodable bytes, anything before `Hello` except `Hello`, a second `Hello`, messages meant for the other direction (`AuthResult`, `RegisterServer`, `UnregisterServer`) and a `ServiceReady` or `ServiceStatus` about another service. | `ConnectionHandler` |
| Closing | After the last message the connection stops reading. If the peer does not take the last message within a second, the connection is cut. | `ConnectionHandler` |
| Backpressure | When more than 2 MiB are queued for a peer, the cloud stops reading from it until the queue is below 512 KiB. A peer that sends pings or requests without reading the answers therefore cannot make the cloud buffer without limit; if it stays stuck, the idle timeout ends it. | `ConnectionHandler` |
| Cluster nodes | Refused (`UNKNOWN_PEER`) on the service channel. | `ConnectionHandler` |

### What an authenticated service can do

The token proves which service a connection belongs to, nothing more. It sits in the environment of the server process, and every plugin in that process can read it. The cloud therefore treats an authenticated service as a semi-trusted peer:

- **Its own reports only.** `ServiceReady` and `ServiceStatus` must name the authenticated service, otherwise the connection ends. Player events from a proxy are passed on as they are; the node has to check them against what it knows (PR 8).
- **No system messages.** `RegisterServer`, `UnregisterServer` and `AuthResult` go from the cloud to services only; a service that sends one is disconnected. A service cannot make another service receive them: `send` and `request` are methods of the cloud, services do not route to each other.
- **Channel messages are not authenticated between services.** A service may publish on any channel, and every other service receives every channel message (they filter by channel on their side). The receiving service cannot see who sent it, so a plugin in one service can read and forge any channel. Cloud subscribers get the authenticated sender (`ChannelSubscriber.received(ServiceId, ...)`). Anything that has to be trusted (server lists, permissions, transfers) uses its own message type or a request to the cloud, never a channel message between services.
- **Takeover only with the current ticket.** The token sits in the environment of the server process: every plugin in it can read it, and on some systems other local processes of the same user can too (`/proc/<pid>/environ`), and it may end up in a crash report. It therefore opens one connection only. Once the bridge has connected, the token is worthless; the bridge keeps the ticket for its next connection in memory and never in the environment or a file. Whoever has the current ticket can connect as the service and replace its connection (`REPLACED`); that needs code inside the process that reads the client's memory. The cloud removes the start, and every ticket of it, when the process ends (`ServiceTokens.remove(id, token)` with the token it issued; it only removes that start, so the late exit of an old process cannot remove the start of its restart).
- **What one connection per token costs.** A bridge that is loaded a second time in the same process (a plugin reload) starts with the token from the environment again and is refused; the service has to be restarted. If a connection breaks after the cloud sent the ticket but before the bridge read it, the bridge has no valid ticket either and is refused until the restart. Both trade an edge case for not letting a copied token in.
- **Limits per connection.** 1 MiB per frame, 256 open requests in each direction, backpressure as above. One service can still load the cloud within those limits, for example by publishing large channel messages that are fanned out to every service; the fan-out skips services that do not keep up.

**No TLS yet.** The channel is plain TCP. On loopback that is fine. If `host` in `nodes.yml` is set to another address, the cloud logs a warning at start; such a setup needs a firewall that only lets the cloud's own machines in. TLS 1.3 with mutual authentication follows with the cluster (stage 2, PR 20). The server has a hook for it already: a `PipelineCustomizer` runs first for every accepted connection and can add an `SslHandler` in front of the handler named `CloudNetworkServer.FRAME_DECODER`. The client accepts any `javax.net.SocketFactory`, so an `SSLSocketFactory` fits without changes to the client.

## Server: `CloudNetworkServer`

```java
ServiceTokens tokens = new ServiceTokens();
CloudNetworkServer server = CloudNetworkServer.builder(NetworkSettings.forNode(node), tokens)
        .listener(channelListener)
        .build();
server.start();

AuthToken token = tokens.issue(serviceId); // before starting the process, goes into VECTORA_SERVICE_TOKEN
// ...
tokens.remove(serviceId, token);           // after the process has ended; false if a newer token replaced it
server.close();                            // Disconnect(SHUTDOWN) to everyone
```

In the running node the tokens belong to the service core: `ServiceManager` holds the only `ServiceTokens`, issues a token right before it starts a process and removes exactly that token when the process ends or the start fails. `CloudNode` passes `ServiceManager.authenticator()` to the builder, so the channel accepts exactly the services that run now, and `ChannelBridge` closes the connection of a service whose process has ended. `CloudNode` starts the server before the first service and closes it after the last one.

### Seams for the service core (`cloud-node`, PR 8)

The network module knows nothing about groups or processes. The node connects it through two small interfaces:

| Interface | Direction | Purpose |
|---|---|---|
| `ServiceAuthenticator` | network asks node | `admit(PeerId, AuthToken)` checks a `Hello` and, if it is accepted, uses the token up in the same step; the `Admission` carries the outcome and the ticket for the next connection. `authenticate(PeerId, AuthToken)` only checks, without using anything up (diagnostics, tests). `ServiceTokens` is the ready-made implementation with `issue`, `register` and `remove(id, token)`. |
| `ChannelListener` (the service core's own listener in `cloud/node` is `ServiceListener`) | network tells node | `onEvent(ServiceEvent)` with one of the records `Connected`, `Ready`, `StatusReported`, `MessageReceived` (`PlayerJoin`, `PlayerQuit`, `PlayerSwitch`) and `Disconnected`. `ServiceEvent` is sealed, so a `switch` can cover every case. Without a listener the events are dropped. |

And the node talks to services through the server:

| Method | Purpose |
|---|---|
| `send(ServiceId, Message)` | One message to one service, for example `RegisterServer` to a proxy. |
| `publish(ChannelMessage)` | A channel message from the cloud to every service. |
| `request(ServiceId, Request)` | A request to a service; the future fails with `TimeoutException` after 10 s or with `ServiceNotConnectedException`. |
| `handleRequests(ChannelName, RequestHandler)` | Answers requests that services send to the cloud on a channel. |
| `subscribe(ChannelName, ChannelSubscriber)` | Receives channel messages that services publish. |
| `disconnect(ServiceId, Reason)`, `connectedServices()`, `isConnected(ServiceId)` | Connection control. |

Rules for implementers:

- Callbacks run on Netty threads and must return quickly. Hand longer work to an executor of your own. An exception from a callback is logged and does not close the connection.
- `close()` waits for the event loops and must not be called from a callback.
- A service that reconnects while its old connection is still open replaces it: the old one gets `Disconnect(REPLACED)` and stops being read at once (frames still in flight are dropped), `Connected` comes again, and no `Disconnected` comes for the replaced connection. If the old peer does not take the `Disconnect` within a second, its connection is cut.
- `Disconnected` carries the reason the service sent or the cloud closed with, or `null` if the connection simply broke.

### Messaging

A channel message that a service publishes goes to the cloud's subscribers of that channel and to every other connected service. The sender does not get it back. Services filter by channel on their side. There is no subscription message in protocol 1.0, so the cloud cannot know which service listens to which channel. With the number of services on one node that is acceptable; if it ever is not, a subscribe message can be added in a minor version.

Channel messages are best effort. If a service does not read and more than 2 MiB are queued for it, further channel messages to it are skipped (`droppedChannelMessages()` counts them) until the queue has drained below 512 KiB. Direct messages (`send`) and requests are always queued.

Requests can go in both directions. The sender picks a request id that is not 0 and unique among its open requests; the response carries the same id. A request on a channel without a handler is answered with `NO_HANDLER`, a handler that throws or fails with `ERROR`. Service-to-service requests are not routed; a service asks the cloud, and the cloud may ask another service.

## Client: `CloudClient`

```java
CloudClient client = CloudClient.create(CloudClientSettings.fromEnvironment());
client.addListener(event -> { }); // CloudClientEvent: Connected, Disconnected, Rejected, MessageReceived (RegisterServer and so on)
client.installShutdownHook();      // Disconnect(SHUTDOWN) even if the server is killed with SIGTERM
client.start();                    // connects in the background, returns at once

client.markReady();                                   // once the server accepts players
client.reportStatus(online, max, tps, mspt);          // every few seconds; heap is filled in
client.send(new PlayerJoin(uuid, name, here, bedrock)); // proxies only
client.publish(channel, bytes);
client.subscribe(channel, message -> { ... });
client.request(channel, bytes).thenAccept(response -> { ... });
client.handleRequests(channel, request -> CompletableFuture.completedFuture(response));

client.close();                    // in onDisable
```

### Environment variables

The cloud starts every service process with these variables. A system property with the listed name overrides the variable, which is meant for manual tests only: `-D` values appear on the command line, which other users of the machine can read.

| Variable | System property | Default | Content |
|---|---|---|---|
| `VECTORA_CLOUD_HOST` | `vectora.cloud.host` | `127.0.0.1` | Host of the cloud channel |
| `VECTORA_CLOUD_PORT` | `vectora.cloud.port` | `7010` | Port of the cloud channel |
| `VECTORA_SERVICE_ID` | `vectora.service.id` | required | Id of this service, for example `lobby-1` |
| `VECTORA_SERVICE_TOKEN` | `vectora.service.token` | required | Token from `ServiceTokens.issue` |

The names are constants of `net.vectora.protocol.ServiceEnvironment` (`CLOUD_HOST`, `CLOUD_PORT`, `SERVICE_ID`, `SERVICE_TOKEN`; `CloudClientSettings.ENV_*` point at them). `cloud/node` sets them from the same constants, so both sides use one definition; the bridges read them in PR 13. A missing or invalid value fails with a message that names the variable and never contains the token.

### Behaviour

- **Reconnect:** after a failed attempt or a lost connection the client waits and tries again. The wait starts at 1 s and doubles up to 30 s, with jitter (half to all of the step), so a restarted cloud is not hit by every service at once. After a successful connection the wait starts at 1 s again. A refused token is retried as well, with the same waits; listeners get a `Rejected` event each time.
- **State after a reconnect:** `ServiceReady` (if `markReady` was called) and the last status report are sent again. Player events and channel messages sent while disconnected are lost; the methods return `false`. Requests fail at once with `NotConnectedException`, open requests fail when the connection drops.
- **Heartbeat:** `Ping` every 10 s. If nothing arrives for 30 s, the client sends `Disconnect(TIMEOUT)` and reconnects.
- **Handshake:** 5 s for connect plus `AuthResult`. Until `AuthResult(ACCEPTED)` and the `SessionTicket` behind it the client also accepts frames of 4 KiB at most.
- **Ticket:** the first `Hello` carries the token from `VECTORA_SERVICE_TOKEN`, every later one the ticket of the last accepted connection. The ticket stays in the client's memory; a `SessionTicket` outside the handshake is a protocol error and never reaches a listener.
- **Status values:** `reportStatus(int, int, double, double)` clamps values into the protocol's ranges (NaN counts as 0) and fills in the heap. `heapMax` is `-1` when the JVM has no limit (`Runtime.maxMemory()` returns `Long.MAX_VALUE`).
- **Threads:** one daemon thread reads the socket, one runs timers, one delivers events. Listener, subscriber and handler calls run on the event thread, one at a time and never on a server thread. A bridge that has to touch the server hands the work to the server's scheduler.

### Interfaces for the platform bridges (PR 13)

How the bridges use them, and what they add on top (send queue, server registry, player state, BungeeGuard check), is described in [bridges.md](bridges.md).

| Type | Use in a bridge |
|---|---|
| `CloudClientSettings.fromEnvironment()` | Read the variables above in `onEnable`. Outside the cloud (variables missing) the bridge logs the message and stays inactive. |
| `CloudClient.create`, `start`, `close`, `installShutdownHook` | Life cycle. `close()` returns within about a second. |
| `markReady()` | Paper: when the server has finished starting. Velocity: when the proxy is initialised. |
| `reportStatus(...)` | Periodic task with players, TPS and MSPT of the platform. |
| `send(PlayerJoin / PlayerQuit / PlayerSwitch)` | Velocity bridge, from the proxy's player events. |
| `CloudClientListener` with `CloudClientEvent.MessageReceived` | Velocity bridge: `RegisterServer` and `UnregisterServer` add and remove backend servers. |
| `publish`, `subscribe`, `request`, `handleRequests` | Plugin messaging between services, the replacement for BungeeCord plugin channels. |

### Why the client uses plain JDK sockets and no Netty

Paper, Velocity, Fabric and PandaSpigot all ship Netty, each in the version its maintainers chose, and the 1.8.8 fork carries a much older line than current Paper. Three options were weighed:

1. **Use the server's Netty.** The client would be compiled against one Netty version and run against several. Netty 4.2 deprecates the 4.1 event loop classes in favour of new ones, so code written for one line is at best deprecated on the other and may break when the old classes go. It also ties the client to whether and how each platform exposes Netty to plugins.
2. **Shade and relocate Netty.** Works everywhere, but adds about 2.5 MB (common, buffer, transport, handler, codec) to every plugin jar and a second Netty copy to every server JVM, and the native transports need extra care when relocated. Every Netty security update would mean a new plugin release.
3. **JDK sockets.** One connection per process does not need non-blocking I/O. A blocking `Socket` with three daemon threads is enough, adds nothing to the class path and behaves the same on every platform and Java version from 21 on.

The client uses option 3. `common/client` depends on `common/protocol` only, which has no dependencies either. The cloud side handles many connections and later HTTP and WebSocket for the API, so it uses Netty there (see [architecture](architecture.md#runtime-libraries)).

## Tests

| Test | What it covers |
|---|---|
| `CloudNetworkServerTest` (cloud-network) | Real loopback connections with a hand-driven peer: loopback default, valid and wrong token, unknown service, removed token (and a stale token that removes nothing), cluster node refused, oversized frame before authentication (and the same size accepted after it), message before `Hello`, garbage, other major version, slowloris and silent new connection, idle timeout and the counter-check with a peer that answers pings, limits per address and in total, the limit for unauthenticated connections in total and per address (an accepted connection leaves it, a refused one gives its slot back), a copied token refused while the bridge stays connected, own and foreign status reports, replacement on reconnect with the ticket (also when the old peer does not read), requests in both directions with timeout and disconnect, channel messages between services, `send` restrictions, shutdown, port in use. |
| `CloudClientEndToEndTest` (cloud-network) | `CloudClient` against the real server: ready and status repeated after a reconnect, cloud started after the client and restarted on the same port, a second client with the same start token refused while the first reconnects with its ticket, wrong token, messaging between two clients, requests in both directions, `RegisterServer` to the listener, `Disconnect(SHUTDOWN)` on close. |
| `ConnectionPipelineTest` (cloud-network) | The pipeline on Netty's `EmbeddedChannel`: frame limits before and after authentication, frames split into single bytes, reading paused while the peer is not writable, `PipelineCustomizer` in front of the decoder; a reused or copied start token refused, a ticket that opens exactly one connection, a 1.1 peer that gets no ticket but still uses its token up, a `SessionTicket` from a service as a violation. |
| `ConnectionLimiterTest` (cloud-network) | The limit keys: IPv4 per address, IPv6 per /64, NAT64 (`64:ff9b::/96`) and IPv4-mapped addresses as their IPv4 address, with counter-checks next to the prefix; per-address limit over a whole /64, NAT64 sharing the limit of its IPv4 host; the unauthenticated count freed by authentication and release. |
| `CloudClientTest`, `FrameReaderTest`, `CloudClientSettingsTest`, `BackoffTest` (CloudClient) | The client against a scripted JDK server socket: `Hello` contents, reconnect with the newest ticket, an acceptance without ticket, a 1.1 cloud without ticket, a stray ticket after the handshake, oversized frame before `AuthResult`, silent cloud, clamped status values, misuse, close; reading in single bytes; the variables; backoff steps. |
| `ConnectionFuzzTest` (cloud-network), `FrameReaderFuzzTest` (CloudClient) | Jazzer: arbitrary bytes into the server pipeline (only valid frames come back, and before a correct `Hello` only `AuthResult` or `Disconnect`) and into the client's reader (only `EOFException` or `ProtocolException`). Seed inputs include a valid `Hello` followed by a session, so the fuzzer reaches the authenticated state. |

All of them run on Java 25 and, with `-PtestJava=21`, on Java 21.
