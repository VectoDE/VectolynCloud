# Cloud protocol

`Vectora-Protocol` (`net.vectora.protocol`) is the binary format that VectoraCloud uses to talk to the services it starts (bridges, later the Vectora plugin) and to other cloud nodes. Both ends share this one module, so they cannot drift apart.

The module has no dependencies, not even Netty. Proxies and servers ship their own Netty versions, and a second copy on their class path invites conflicts. The codec works on `byte[]` and `java.nio.ByteBuffer`; the transport (Netty in `cloud-network`, plain sockets in `Vectora-CloudClient`) only moves bytes.

## Layers

| Layer | Classes | Job |
|---|---|---|
| Frame | `Frame`, `FrameCodec` | Length prefix, version, message id, request id, raw payload. Finds frame borders in a byte stream. |
| Payload | `PacketWriter`, `PacketReader`, `VarInts` | Primitives, strings, byte arrays and lists with fixed upper limits. |
| Message | `Message` and its records, `MessageRegistry`, `MessageCodec`, `Envelope` | Typed messages, their ids, and the request id rules. |
| Values | `ServiceId`, `NodeId`, `GroupName`, `ChannelName`, `AuthToken`, `ProtocolVersion` | Validated value types used inside messages. |

Typical use:

```java
MessageCodec codec = MessageCodec.standard();
byte[] bytes = codec.encode(Envelope.of(new Ping(42)));

// Receiving side, bytes arriving in chunks:
Optional<Envelope> next = codec.tryDecode(buffer); // empty until a whole frame is there
// Before AuthResult(ACCEPTED), with the small handshake limit:
Optional<Envelope> hello = codec.tryDecode(buffer, Protocol.MAX_HANDSHAKE_FRAME_SIZE);
```

`MessageCodec.tryDecode` reads the message straight from the stream buffer: `FrameCodec.tryReadBody` hands out a read-only view of the frame body and `FrameHeader.read` parses its header, so the payload is not copied on the way. Only the message records copy their own byte arrays, once when reading and once in the constructor that keeps them immutable. The `Frame` path (`FrameCodec.decode`, `MessageCodec.fromFrame`) is still there for tests and tools.

## Frame layout

Every frame on the stream:

| Field | Type | Notes |
|---|---|---|
| `length` | VarInt | Size of everything after this field. 4 to 1,048,576 (1 MiB), and at most 4,096 (4 KiB) until the cloud has sent `AuthResult(ACCEPTED)`. Checked as soon as it arrives, before the body is buffered. |
| `major` | u8 | Protocol major version of the sender. |
| `minor` | u8 | Protocol minor version of the sender. |
| `messageId` | VarInt | Message type, see the table below. Not negative. |
| `requestId` | VarInt | Pairs a request with its response. `0` means none. Not negative. |
| `payload` | bytes | The rest of the body, encoded as described for the message. |

Example: a frame with message id `0x11`, request id `300` and the payload `07 08` is `07 01 00 11 AC 02 07 08`.

## Encoding rules

- Multi-byte numbers (`short`, `int`, `long`, `double`) are big-endian.
- VarInt and VarLong: 7 data bits per byte, least significant group first, high bit set while more bytes follow. At most 5 bytes (VarInt) or 10 bytes (VarLong). Only the shortest encoding is valid, and bits beyond 32 or 64 are an error.
- Booleans are one byte, `0` or `1`. Anything else is an error.
- Strings are UTF-8 with a VarInt length prefix that counts bytes, not characters. Invalid UTF-8 (overlong forms, encoded surrogates, cut-off sequences) is an error. The writer refuses strings with unpaired surrogates.
- Byte arrays and lists carry a VarInt length or element count. Every read names its upper limit; nothing is allocated before the length has been checked against it.
- UUIDs are two longs, most significant first.
- Enums travel as VarInt codes. The codes are fixed in the source and listed below; an unknown code is an error.

Because every value has exactly one valid encoding, a frame that decodes encodes back to the same bytes. The fuzz test `decodedFramesEncodeToTheSameBytes` checks this.

## Errors

For bad input the decoders throw only `ProtocolException`, never `BufferUnderflowException`, `IllegalArgumentException` or similar. That covers:

- a length that is negative or above its limit, including the frame length,
- input that ends early, and bytes left over after a frame or after a message,
- VarInt or VarLong overflow and non-minimal encodings,
- invalid UTF-8, booleans other than 0 and 1, unknown enum codes and message ids,
- values a message record rejects (an invalid service id, a NaN TPS value, port 0),
- a request id that does not fit the message (see below).

A frame with another major version raises `IncompatibleVersionException`, a subclass that carries the remote version. The receiver may send `Disconnect(INCOMPATIBLE_VERSION)` in its own version and then closes the connection. After any `ProtocolException` the connection is closed; the stream cannot be resynchronised.

Exception messages never contain the offending string itself, since it may hold control characters meant for a log file.

## Messages

All messages are immutable records in `net.vectora.protocol.message`, listed in `MessageRegistry.standard()`.

| Id | Message | Direction | Payload |
|---|---|---|---|
| `0x00` | `Hello` | peer → cloud | version (2 × u8), role (u8: 0 service, 1 node), id (string ≤ 64), token (string 16–256) |
| `0x01` | `AuthResult` | cloud → peer | outcome (VarInt), cloud version (2 × u8) |
| `0x02` | `Ping` | both | nonce (long) |
| `0x03` | `Pong` | both | nonce of the ping (long) |
| `0x04` | `Disconnect` | both | reason (VarInt) |
| `0x10` | `ServiceReady` | service → cloud | service id (string ≤ 64) |
| `0x11` | `ServiceStatus` | service → cloud | service id, players online and max (VarInt, 0 to 1,000,000), TPS (double, 0 to 1000), MSPT (double, 0 to 60,000), heap used (VarLong, ≥ 0), heap max (VarLong, ≥ 0, or `-1` for unknown or unlimited) |
| `0x20` | `RegisterServer` | cloud → proxy | name (service id), host (string ≤ 253, DNS name or IPv6 literal, see below), port (u16, 1 to 65535), group (string ≤ 32), legacy (bool) |
| `0x21` | `UnregisterServer` | cloud → proxy | name (service id) |
| `0x22` | `ServerCapacity` | cloud → proxy | name (service id), slots (VarInt, 1 to 1,000,000), open (bool). Since 1.1. |
| `0x30` | `PlayerJoin` | proxy → cloud | UUID, name (string ≤ 32), service id, Bedrock (bool) |
| `0x31` | `PlayerQuit` | proxy → cloud | UUID, name, service id, Bedrock (bool) |
| `0x32` | `PlayerSwitch` | proxy → cloud | UUID, name, from (service id), to (service id, different from `from`), Bedrock (bool) |
| `0x33` | `TransferPlayer` | cloud → proxy | UUID, to (service id), reason (VarInt). Since 1.1. |
| `0x40` | `ChannelMessage` | both | channel (string ≤ 64, `[a-z0-9_][a-z0-9_.-]*`), payload (bytes ≤ 64 KiB) |
| `0x50` | `Request` | both | channel, payload (bytes ≤ 64 KiB) |
| `0x51` | `Response` | both | status (VarInt), payload (bytes ≤ 64 KiB) |

Codes:

| Enum | Codes |
|---|---|
| `AuthResult.Outcome` | 0 `ACCEPTED`, 1 `INVALID_TOKEN`, 2 `UNKNOWN_PEER`, 3 `INCOMPATIBLE_VERSION`, 4 `ALREADY_CONNECTED` |
| `Disconnect.Reason` | 0 `NORMAL`, 1 `SHUTDOWN`, 2 `AUTH_FAILED`, 3 `INCOMPATIBLE_VERSION`, 4 `PROTOCOL_ERROR`, 5 `TIMEOUT`, 6 `REPLACED` |
| `Response.Status` | 0 `OK`, 1 `ERROR`, 2 `NO_HANDLER`, 3 `DENIED` |
| `TransferPlayer.Reason` | 0 `SCALE_DOWN` |

Ids are grouped by area: `0x0_` connection, `0x1_` service state, `0x2_` server list, `0x3_` players, `0x4_` messaging, `0x5_` request/response. New messages take the next free id in their group.

`ServerCapacity` tells a proxy how many players a backend is meant for (`players-per-service` of its task) and whether it takes new players; the node sends it after each `RegisterServer` and again when the slots change or the backend is closed for a scale-down or opened again. `TransferPlayer` asks a proxy to move one of its players to another backend of the same group; the proxy carries it out only if the player is on a backend of that group and the target is listed, open and a different backend. Only the cloud sends either message: from a peer, the cloud treats them as a protocol error and closes the connection. See [scaling.md](scaling.md#where-players-go) and [bridges.md](bridges.md).

### Field rules

| Value | Rule |
|---|---|
| `GroupName`, `NodeId` | 1 to 32 characters, `[a-z0-9][a-z0-9_-]*`. Not a Windows device name (see below). |
| `ServiceId` | 1 to 64 characters, same pattern and the same device-name rule. A service id is usually `<group>-<index>`, so a group name of full length still fits. |
| `ChannelName` | 1 to 64 characters, `[a-z0-9_.-]`, starting with a letter, digit or `_`. That rules out `.`, `..`, hidden-file names like `.x` and option-like names like `-x`. |
| Host (`RegisterServer`) | Either a DNS name, labels of 1 to 63 letters, digits and hyphens without a hyphen at either end, separated by single dots, no trailing dot (IPv4 addresses match this as well), or an IPv6 literal without brackets and zone id: 2 to 8 groups of up to 4 hex digits separated by colons, optionally ending in an IPv4 address, at most 45 characters. Never a port. |
| `AuthToken` | 16 to 256 printable ASCII characters without spaces. `toString()` prints `AuthToken[redacted]`; compare with `matches`, which runs in constant time. |
| Player name | 1 to 32 printable ASCII characters, no space at either end. Java names have at most 16; the rest leaves room for a Floodgate prefix and Bedrock gamertags with inner spaces. |

Group names, node ids and service ids become file and directory names. That is why they have no dots or slashes and only lower-case letters: Windows and macOS do not tell upper and lower case apart in file names, so `Lobby` and `lobby` would be two names for one directory. For the same reason the Windows device names `con`, `prn`, `aux`, `nul`, `com0` to `com9` and `lpt0` to `lpt9` are rejected; Windows cannot create a file or directory with such a name. The check is exact, so `con1` or `console` are fine.

`ChannelMessage`, `Request`, `Response` and `Frame` copy their byte arrays in and out and print only the length in `toString()`, so a payload never ends up in a log by accident.

### Request and response

`Request` and `Response` are the only messages that carry a request id, and they must carry one (not `0`). Every other message must use `0`. `Envelope` enforces this when a message is built and when it is decoded.

The sender of a request picks an id that is unique among its open requests (a counter that skips `0` on wrap-around is enough). The receiver answers with a `Response` that has the same id. The handler is found by the channel name of the request; without one the answer is `NO_HANDLER`. Timeouts are the caller's business and not part of the wire format.

### Connection flow

1. The peer connects and sends `Hello` with its version, role, id and token. Until step 2 is done, both sides read frames of 4 KiB at most; the largest `Hello` is about 330 bytes. A larger length prefix closes the connection right away, before the body is read.
2. The cloud checks the token and answers `AuthResult`. On anything but `ACCEPTED` it closes the connection. After `ACCEPTED` both sides allow the full 1 MiB.
3. Both sides send `Ping` at intervals and answer with `Pong`. A side that hears nothing for too long closes with `Disconnect(TIMEOUT)`.
4. Either side ends the connection with `Disconnect` and a reason.

The token comes from the cloud: a fresh service token per start, or the join token for cluster nodes. Cluster links also run over mTLS with TLS 1.3; the protocol does not replace transport security.

## Versions

The current version is `Protocol.PROTOCOL_VERSION`, **1.1**.

- **Same major version required.** Peers with different major versions cannot talk. A major version changes when the frame layout, an existing message id or the encoding of an existing message changes.
- **Minor versions only add.** A new message type, a new enum code or a new value range that old peers would reject needs a new minor version. Existing payloads stay as they are.
- **The lower minor wins.** A connection uses the lower minor version of both peers (`ProtocolVersion.negotiate`). Neither side sends a message or code that came after it, because the older side would reject it as unknown. Each message type knows the version that added it (`MessageType.since`, `availableIn`); the cloud keeps the negotiated version per connection and drops (with a DEBUG line) a message the peer's version does not have. A 1.0 proxy therefore keeps working: it gets no `ServerCapacity` or `TransferPlayer`, picks servers the way Velocity does, and a scale-down of a backend with its players on it ends with the backend kept running.
- **The frame header never changes within a major version,** so a peer can always read the version of a frame and answer an incompatible one with `Disconnect(INCOMPATIBLE_VERSION)`.

Version history:

| Version | Changes |
|---|---|
| 1.0 | First version: 15 messages. Before the first release (stage 2, PR 9) the handshake frame limit was added, `ServiceStatus` accepts a heap maximum of `-1`, and channel names and hosts got the stricter rules above. No peer with the earlier rules was ever released, so the version stayed 1.0. |
| 1.1 | `ServerCapacity` (`0x22`) and `TransferPlayer` (`0x33`, reason `SCALE_DOWN`), both cloud → proxy, for the choice of the emptiest server and the moves before a scale-down. |

## Limits at a glance

| What | Limit |
|---|---|
| Frame body | 1 MiB (`Protocol.MAX_FRAME_SIZE`); 4 KiB before `AuthResult(ACCEPTED)` (`Protocol.MAX_HANDSHAKE_FRAME_SIZE`) |
| `ChannelMessage`, `Request`, `Response` payload | 64 KiB (`MessageLimits.MAX_PAYLOAD_SIZE`) |
| Service id, channel name | 64 characters |
| Group name, node id | 32 characters |
| Player name | 32 characters |
| Host | 253 characters |
| Token | 16 to 256 characters |
| Player counts | 0 to 1,000,000 |
| TPS / MSPT | 0 to 1000 / 0 to 60,000, finite |

## Tests

- Round trip for every message, once with typical values and once with every length at its limit (`MessageRoundTripTest`).
- Limits and invalid input for every length, value type and enum (`PacketCodecTest`, `IdentifierTest`, `MessageValidationTest`, `MessageCodecTest`, `FrameCodecTest`, `VarIntsTest`).
- Registry without duplicate ids, complete against the sealed `Message` interface, and pinned to the id table above (`MessageRegistryTest`).
- Jazzer fuzz tests: `MessageCodecFuzzTest` (decode throws only `ProtocolException`; decoded frames encode back to the same bytes; payloads of every message type; stream decoding; the copy-free path accepts exactly what the frame path accepts; the handshake limit holds), `PacketReaderFuzzTest` (strings, lists) and `VarIntsFuzzTest`. They replay their seed inputs in every test run and are fuzzed nightly, see [architecture](architecture.md#fuzz-tests).
