# Rights audit

Every privileged command and action of VectoraCloud and its plugins, who may use it and where that is checked. The rule: a moderation, admin or team action is checked by the server that carries it out, for every call, never only by a client or a menu, and it is off for normal players unless an operator or a permission plugin grants it. Command blocks do not exist on the network at all ([bridges.md](bridges.md#command-blocks)).

This list is part of the repository and changes with the code. A pull request that adds a privileged command or action adds its row here, with the test that shows a player without the right triggers nothing.

State: stage 2 (VectoraCloud core). The Vectora plugin with its own moderation, ranks and menus comes in stage 3; its commands join this list then. There are no dashboard actions in game yet.

## In game

| Command or action | Where | Who may | Checked where | Default for normal players | Test |
|---|---|---|---|---|---|
| `kick`, `minecraft:kick` (network kick) | Paper bridge | console, RCON, players with `vectora.moderation.kick`; `execute` as its caller | Brigadier requirement of the bridge's node (the command does not exist for others) and again in `networkKick` | off (`default: op` in `plugin.yml`) | `KickCommandNodeTest.anUnentitledSourceCannotUseIt`, `KickSourceTest` (paper), `PluginDescriptorTest` (paper) |
| `kick`, `minecraft:kick`, `vectoracloud:kick` (network kick) | legacy bridge (1.8.8) | as above | `NetworkKickCommand` before anything else, and again in `networkKick`; selectors go to the vanilla command, which needs `minecraft.command.kick` | off (`default: op`) | `NetworkKickCommandTest.anUnentitledSenderTriggersNoNetworkKick`, `KickSourceTest` (legacy) |
| `/kick` of another plugin turned into a network kick (command line watch) | Paper and legacy bridge | entitled senders as above | `KickCommandWatch.entitled` for the line's sender | off | `KickCommandWatchTest.anUnentitledPlayerOrACommandBlockTriggersNoNetworkKick` |
| `networkKick(issuer, player, reason)` for the moderation module | Paper and legacy bridge | entitled issuers as above | in the method itself, returns `false` and does nothing otherwise | off | `KickSourceTest` (paper, legacy) |
| Joining a server the cloud closed | Velocity bridge | players with `vectora.server.bypass-closed` | `ServerPreConnectEvent` on the proxy (`ProxyBridge.mayConnect`) | off (Velocity grants no permission without a permission plugin) | `ProxyBridgeTest` (closed server refused, bypass, counter-checks) |
| Breaking and placing blocks in the lobby | VectoraLobby | players with `vectora.lobby.build` | every `BlockBreakEvent` and `BlockPlaceEvent` on the lobby server (`BuildProtection`) | off (`default: op`) | `BuildProtectionTest` |
| End portal routing | VectoraPortalBridge | every player, no privilege | | | |
| Command blocks and command block minecarts | every Paper and 1.8.8 server | nobody, also not operators | node (`enable-command-block=false`) and bridges (`CommandBlockGuard`) | not obtainable, not placeable, not usable | `CommandBlockSettingTest`, `RealTemplatesTest.noTemplateShipsCommandBlocks`, `CommandBlockGuardTest` (paper, legacy) |
| Vanilla commands (`/op`, `/give`, `/gamemode`, ...) | Paper, PandaSpigot, Fabric | operators | the server; the templates set `op-permission-level=4` and ship no `ops.json` | off | |

Plugins of the transitional base in the templates (LuckPerms, WorldEdit, WorldGuard, TAB, PlaceholderAPI, the Fabric mods) bring their own commands and check their own permissions on the server. They are not audited here one by one: they are replaced by the Vectora plugin from stage 3 on. WorldEdit's `command-block-support` is off in every template that ships WorldEdit.

The Fabric mod server has no Vectora bridge yet; command blocks are off there through `server.properties`, and removing them from the world follows with the Fabric bridge ([#79](https://github.com/VectoDE/VectoraCloud/issues/79)).

## Cloud channel

Only services the cloud started talk on the channel, each with a token of its own ([protocol.md](protocol.md)). A player's client never reaches it. The token in the environment of a server process opens one connection; after that only the ticket the cloud sent over that connection gets in, so a plugin or local process that copies the token from the environment is refused (`ConnectionPipelineTest.aReusedOrCopiedStartTokenIsRefused`, `CloudClientEndToEndTest.aSecondClientWithTheSameStartTokenIsRefused`, [network.md](network.md#security-model)). What a service may send, and what the cloud and the proxies accept:

| Message from a service | Accepted when | Otherwise | Test |
|---|---|---|---|
| `ServiceReady`, `ServiceStatus` | it names the sender itself | the connection is cut off | `ConnectionPipelineTest.readyOnlyForTheSenderItself` |
| `NetworkKick` | it names the sender itself (network layer), the sender is a backend on the proxies' lists (node), and the proxy listed that server and the player is on it or was kicked from it within 10 seconds (proxy) | cut off, dropped or ignored | `ConnectionPipelineTest.networkKickOnlyForTheSendersOwnServer`, `ChannelBridgeDrainTest.networkKicksArePassedOnOnlyFromListedBackends`, `ProxyBridgeTest.aNetworkKickIsIgnoredForAnUnlistedServerOrAPlayerWhoIsNotOnIt` |
| `PlayerJoin`, `PlayerSwitch`, `PlayerQuit` | the sender is a proxy and the server name is one of its backends | dropped | `NetworkPlayersTest.reportsThatDoNotFitAreDropped` |
| `RegisterServer`, `UnregisterServer`, `ServerCapacity`, `TransferPlayer` | never: only the cloud fills the proxies' lists and moves players | the connection is cut off | `ConnectionPipelineTest.transferCapacityAndServerListsFromAServiceAreProtocolViolations` |
| `SessionTicket` | never: only the cloud hands out tickets | the connection is cut off | `ConnectionPipelineTest.aTicketFromAServiceIsAViolation` |
| `ChannelMessage` | always, passed on to the other services | no bridge subscribes to a channel, so it changes nothing on a proxy | |
| `Request` | a handler for its channel exists | `NO_HANDLER`; the node registers none | |

## Cloud console and API

| Action | Who may | Checked where | Test |
|---|---|---|---|
| Console commands of the node (services, groups, tasks, tokens, backups, ...) | whoever has the node's terminal: the operator | the node only reads its own standard input; no player, service or network peer reaches it | |
| `POST /services`, `.../stop`, `.../restart`, `.../command` | API tokens with `services:write` (or `admin`) | the API server for every request | `ApiServerTest.aTokenWithoutTheScopeGets403`, `RouteScopesTest` |
| `PUT`/`DELETE` of groups, tasks, nodes, wanted switches, `PUT /config`, `POST /config/reload` | tokens with `config:write` | the API server | `ApiServerTest`, `RouteScopesTest` |
| `POST /backups` | tokens with `backups:write` | the API server | `ApiServerTest`, `RouteScopesTest` |
| Every `GET` | tokens with the read scope of its area; `GET /tokens/self` any valid token | the API server | `RouteScopesTest` |

Details of the scopes: [api.md](api.md).
