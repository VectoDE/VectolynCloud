# Crossplay

[Deutsch](Crossplay-DE)

Goal: Java clients from 1.8 to 26.3 and Bedrock clients play on the same servers.

## How players get in

| Client | Path |
|---|---|
| Java, latest version | Velocity → backend directly |
| Java, other versions | ViaVersion translates newer clients; ViaBackwards and ViaRewind cover older clients down to 1.8 |
| Bedrock | Geyser translates the protocol, Floodgate authenticates Bedrock players without a Java account |

All of this runs on the proxy. Backends run the newest Minecraft version and do not need to know which client a player uses.

## Current state

- The proxy runs Velocity, Geyser, Floodgate, ViaVersion 5.12.2, ViaBackwards 5.12.2 and ViaRewind 4.2.0. ViaBackwards and ViaRewind translate for older Java clients down to 1.8 ([#5](https://github.com/VectoDE/VectoraCloud/issues/5)).
- Floodgate runs on every backend, the Fabric mod server included (Floodgate-Fabric 2.2.7-b69, [#4](https://github.com/VectoDE/VectoraCloud/issues/4)). All servers share one Floodgate key.
- Geyser accepts both Bedrock transports (`transport: both`): RakNet on UDP 19132, NetherNet with built-in signaling on TCP 19132 and its WebRTC traffic on UDP 19133 (always `bedrock-port` + 1). Forward all three when the network is reachable from outside.

## Rules for game content

- Every game mode has to be playable on Bedrock. A mode Bedrock players cannot enter will not ship.
- Menus are inventory menus, which Geyser shows on Bedrock too. Bedrock forms are not used, so both editions see the same menu.
- The mod server only uses server-side mods, because Bedrock clients cannot install Fabric mods.
- The planned anti-cheat (stage 6) treats Bedrock movement separately so Bedrock players do not get flagged for normal behaviour.
- The planned resource pack (stage 7) comes as a Java pack for every `pack_format` from 1.8 to 26.3 and as a Bedrock pack for Geyser.

## Plugin versions (planned, stage 3)

The Vectora plugin covers Minecraft 1.8.8 to 26.3 through version adapters. For 1.8.8 server groups a modern fork that runs on Java 21 will be chosen in stage 2.
