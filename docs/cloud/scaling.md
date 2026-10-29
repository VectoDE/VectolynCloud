# VectoraCloud autoscaling

Package `net.vectora.cloud.node.scaling` in `VectoraCloud/cloud-node`. The node keeps `min-services` of every task running, adds dynamic services when the load of a group is high, and empties and removes them when it is low. German version: [scaling.de.md](scaling.de.md).

## Configuration

Scaling is set per task in `tasks/<name>.yml` (see [config.md](config.md#tasksnameyml)):

```yaml
schema-version: 2
group: lobby
scaling:
  static-services: 1         # always run, keep their folder, never stopped by the scaler
  min-services: 1            # always running, static ones included
  max-services: 4            # never more, also not by hand
  players-per-service: 50
  scale-up-at: 75            # load of the group in percent that starts one more service
  scale-down-below: 50       # load in percent that one service fewer must stay below
  scale-down-after: 5m       # the low load must hold this long before a service goes
  cooldown: 60s              # at least this long between two decisions of the task
nodes: []
```

### Static and dynamic in one task

`static ≤ min ≤ max` must hold, and `scale-down-below` must be lower than `scale-up-at`.

- **Static services** (`static-services`): the first ones of the task. Each has its own folder under the group's template (`runtime`, `runtime-2`, …) that survives every stop, and the scaler never stops one.
- **Dynamic services**: everything above `static-services`, up to `max-services`. Each runs in `temp/services/<id>`, and that folder is deleted when the service stops.

A new service is static as long as the task has fewer static services than `static-services`, otherwise dynamic. The lobby above therefore has one static lobby and up to three dynamic ones. `static-services = max-services` is a purely static task (it never scales), `static-services = 0` a purely dynamic one.

Lowering `static-services` later turns the surplus static services into dynamic ones, from the highest index down: with three static lobbies and `static-services: 1`, lobby-2 and lobby-3 count as dynamic. They are scaled down like any dynamic service (emptied first, see below) but keep their folder when they stop, and a stopped one no longer counts towards `min-services`. Raising it makes the next new service static.

Suggested values from the setup (static/min/max): proxy and legacy proxy 1/1/1, survival, creative, mod and end 1/1/1, lobby 1/1/4, legacy 1.8.8 server 1/1/3, minigame 0/1/8; `scale-up-at` 75, `scale-down-below` 50.

The file, the console and the API all change it through `ConfigService.apply` and the same validator:

| Way | Example |
|---|---|
| File | edit `tasks/minigame.yml`; the node reloads it after 200 ms |
| Console | `task set minigame max-services 6`, `task set lobby static-services 2`, `task set minigame scale-down-below 40` |
| API | `PUT /api/v1/tasks/minigame` with the whole task as JSON (scope `config:write`) |

The scaler reads the configuration again in every round, so all three take effect the same way, at the latest after one round (5 seconds). An invalid change is refused with the same errors everywhere and changes nothing.

### Group settings for the scaler

`drain-timeout` in `groups/<name>.yml` (5s to 10m, default 30s) is how long a service of the group may take to send its players elsewhere before the scaler stops it. See rule 4 below. File, `group set <name> drain-timeout 45s`, `PUT /api/v1/groups/{name}`, `drain-timeout:` in the setup file or the advanced settings of the setup assistant.

### Node settings for the scaler

In `nodes.yml` each node may set `max-memory` (at least 512M, missing: worked out by the node) and `memory-reserve` with `system` (default 1G), `node-overhead` (default 256M) and `database` (default 512M, counted only when the cloud runs the database in `managed` or `docker` mode), each 1M to 64G. The reserve is memory the node keeps for itself. The [memory budget](#memory-budget) uses both. File, `node set <id> <field> <value>`, `PUT /api/v1/nodes/{name}`, `node:` in the setup file or the advanced settings of the setup assistant. See [config.md](config.md#nodesyml).

## Which tasks are scaled

Only tasks of this node (`nodes` empty or naming this node) whose group is wanted. "Wanted" is the one rule `GroupRules.isWanted` in cloud-config that also decides planning, starts, the proxy lists and `runs` in the API: the group's own `wanted`, its switch under `servers:` in `cloud.yml`, and `legacy-proxy.enabled` for the 1.8.8 groups (see [node.md](node.md#which-services-run)). A group that is switched off is never scaled; its services stop on the reload as before.

Nothing is scaled before the setup is complete and the EULA accepted, while the `setup` command saves its steps, and while the node shuts down.

## Load

The **load of a group** is the players on its running services divided by their slots, where each service has `players-per-service` of its own task as slots. Static and dynamic services count alike; a service that is being emptied (below) does not count. Example: lobby-1 (static) with 10 players and lobby-2 with 70, 50 slots each: 80 of 100, 80 %.

The load is not known while a running service of the group has no known player count (see [signals](#signals)). Then the group is neither scaled up nor down.

## Rules

Every 5 seconds the scaler looks at each task once. After a decision by load (rules 3 and 4) the task waits `cooldown` before the next one.

1. **min-services.** If fewer services count than `min-services`, all missing ones start in the same round, without `cooldown`, for example after the EULA was accepted again.
   - A dynamic service counts while it is prepared, starting, running, or crashed and waiting for its restart.
   - A static service within `static-services` always counts, also when it is stopped or gave up after its crashes. A server stopped with `service stop` therefore stays down. Only a static service that stopped because its group was switched off (or because `eula-accepted` or `setup-complete` was taken back) comes back once the switch is on again, at its own index and in its own folder. Whether a switch caused the stop is judged by the configuration at the moment of the stop.
   - A new static slot (a new task, a higher `static-services`) gets the lowest index of the group that no service has used in this run, so a folder of a server stopped by hand is not touched.
2. **Above max-services.** If a task has more services than `max-services` (the limit was lowered), an empty dynamic service is removed the same way as in rule 4: closed on the proxies, checked once more, then stopped. A service with players is never removed for this.
3. **Scale up.** One more dynamic service starts when the load of the group has reached `scale-up-at`, no service of the group is still starting, no other task of the group started one in the same round, the task may have dynamic services (`static-services < max-services`), `max-services` leaves room, and the memory budget allows it (below). With 50 slots per service and 75 %: 75 of 100 players on two services.
4. **Scale down.** The task has more than `min-services`, and without its emptiest running dynamic service the rest of the group would stay below `scale-down-below`, with the players of that service counted on the rest. Once that has held without a break for `scale-down-after`, the service goes:
   - it is always **closed** on the proxies first, so no new player gets there;
   - every player on it is moved to the other services of the group (static ones included). The node reads the players again on every check, so someone who joined in between is moved too. A player who is still on it 2 seconds after his move was sent gets it again, then after 4 and every 8 seconds, in case a proxy lost or refused the first one. Each player gets a short message in their client language (German for `de`, English otherwise);
   - it stops only when it is empty: the proxies list nobody on it **and** its own bridge reports 0 players. Here the higher of both counts decides, so a proxy that has not seen a join yet cannot get a server stopped under a player;
   - if players are still on it after the group's `drain-timeout` (default 30 seconds; a move failed, the proxy is gone), it keeps running and is opened again. If no other open server of the group can take its players, this happens at once, without waiting for the `drain-timeout`. Nobody is kicked. The task then waits `scale-down-after` again before the next try;
   - if the stop itself fails after it was emptied, it is opened again too;
   - while it is being emptied it counts neither for `min-services` nor for the load, and the task is not blocked: if another service of the task crashes meanwhile, its replacement starts in the next round.

   Of equally empty services the one with the highest index goes. Example with 50 slots and 50 %: 20 and 4 players on two services, 24 of 50 without the second one (48 %), so the second one is emptied. With 20 and 5 it is exactly 50 % and both stay.

Because `scale-down-below` is lower than `scale-up-at`, a service that was just removed does not start again in the next round.

What never happens:

- A static service within `static-services` is never stopped or emptied by the scaler. It does take players from an emptied dynamic one. Only the surplus ones after `static-services` was lowered count as dynamic (see above).
- A proxy (`software: velocity`) is never scaled up or down; there is one proxy per node.
- A player is never kicked to scale down.
- `min-services: 0` starts nothing. The first service of such a task comes from `service start`; from then on it is scaled.

A dynamic service stopped with `service stop` is replaced if the task falls below `min-services`, because dynamic services are a pool. To take the dynamic part of a task down, set `min-services` to `static-services`, switch its group off, or remove the task.

## Where players go

The proxy sends a player to the server of the group with the lowest load in percent (players / slots); of equal ones to the one with fewer players, then by name. Players on their way to a server count there for 10 seconds, so a burst of joins is spread. A full server (players, those on their way included, at or above its slots) is skipped. This applies to:

- the first server on join (Velocity's `try` list only decides the group);
- a redirect into a group, for example the fallback to the lobby after a kick;
- the moves of an emptied server (the node plans them with the same rule, `TransferPlanner`);
- a player whose server stopped, crashed, lost the connection or is full: another server of the same group, else the lobby, else the player is disconnected with the original reason. Bans, the whitelist and kicks someone meant are never caught; each server is tried once per incident, and at most 3 kicks per player are caught within 60 seconds (details in [bridges.md](bridges.md#catching-kicks)).

The main proxy and the legacy proxy work the same way. The node tells the proxies the slots of each backend and whether it is open with `ServerCapacity` (see [protocol.md](protocol.md)); a backend that is being emptied is closed. The proxies then refuse every switch to it, `/server` included, except for players with the permission `vectora.server.bypass-closed`; only the first server of a login is let through (see [bridges.md](bridges.md#closed-servers)). A backend without slots (sent by an older node) is chosen the way Velocity would.

## Signals

| What | Source |
|---|---|
| Players per service | The newest sample of the [monitor](monitor.md): `ServiceStatus.playersOnline` that the service's bridge reports every 5 seconds, at most its own `maxPlayers`. A sample older than `stale-after` (60 s) is not used. While a proxy that lists the service is connected, the count is the lower of the bridge's and the proxy's (`PlayerJoin`, `PlayerSwitch`, see [bridges.md](bridges.md#player-events)): a manipulated server that reports more players than the proxy sees on it cannot start servers. Without such a proxy the bridge's count is used. |
| Memory of the node | The newest node sample of the monitor (`node-memory`: used and total memory of the machine, or of the container where Java sees a limit). |

A count that is not known (no report yet, an outdated one, a server without bridge) is never full and never empty. It makes the load of its group unknown, which blocks both scale-up and scale-down of the group. The node logs this once per service as a warning (`Scaling: group … is not scaled while … reports no player count`) and again only after the count was known in between. With `enabled: false` in `monitor.yml` there are no samples: the scaler then keeps `min-services` and does not scale up or down.

## Memory budget

A scale-up (rule 3) also needs:

- the maximum heaps (`heap.max`) of every service on the node that has or is about to get a process, plus the new one, to fit into the **budget** of the node;
- at least the new service's `heap.max` to be free right now.

The budget is `max-memory` of the node in `nodes.yml`, at most the measured memory of the machine (or container). Without `max-memory` the node keeps a reserve free and uses the rest. The sizes come from `memory-reserve` of the node in `nodes.yml`; a missing field (or a node without an entry) has the default:

| Reserve | Field | Default |
|---|---|---|
| Operating system | `system` | 1 GiB |
| The node's own JVM | `node-overhead`, plus its maximum heap (`-Xmx`, or what the JVM chose) | 256 MiB for metaspace, threads and buffers |
| MariaDB run by the cloud | `database`, only if `servers.database` is on and `database.mode` is `managed` or `docker` | 512 MiB |

Example with the defaults: a 16 GiB machine, the node started with `-Xmx1G`, a managed database: 16 - 1 - 1 - 0.25 - 0.5 = 13.25 GiB for the heaps of the services. A database the operator runs elsewhere (`external`) needs no reserve here. Set `max-memory` when other programs share the machine.

If either fails, or the node's memory is not measured yet, the task waits and the log says why, once until the reason changes:

```text
Scaling: task minigame needs another service (1G heap), but the maximum heaps of the services on this node would exceed its memory
```

`min-services` is not held back by the budget, the same as on the start of the node: whoever sets the minimum asks for those servers. `service start` by hand is not checked either.

## Failed starts

A start that fails (download, template, port) or a crash of a service of the task counts as a failure. After 3 in a row, without a service of the task that has run for 5 minutes in between, the scaler starts nothing for that task for 10 minutes and logs it. A change of the task file or its group file ends the pause at once. The restart after a crash (`process.restart` of the group) is not affected; it has its own limit.

## Log

Every start and stop is logged with its reason:

```text
Scaling: minigame-3 of task minigame is started (the load of the group has reached 75 %)
Scaling: minigame-3 of task minigame is stopped (with one service fewer the load stays below 50 %, for 5m)
Scaling: the players of minigame-2 of task minigame are moved to other servers (with one service fewer the load stays below 50 %, for 5m)
Scaling: minigame-2 keeps running: players are still on it after 30 s, a move did not work
Scaling: minigame-2 keeps running: no other open server of its group can take its players
Scaling: minigame-2 keeps running: moving its players was interrupted
```

The last three lines say why a service was not emptied: the time ran out, no other open server of the group was there, or the wait was interrupted (the node is shutting down).

## Ids, folders and proxies

A scaled service is an ordinary service of the service core ([node.md](node.md#ids-folders-and-ports)): id `<group>-<index>` with the lowest free index, dynamic folders in `temp/services/<id>`, a port from the node's `service-ports`, and the proxies learn about it through `RegisterServer` once it is running and through `UnregisterServer` when it stops.

## Code

| Class | Does |
|---|---|
| `ScalingPlanner` | The rules above as a pure function of configuration, services, signals and what earlier rounds remember. No I/O, no clock. |
| `Autoscaler` | The rounds on the thread `vectora-scaling`; starts, stops and drains on `vectora-scaling-action` (the starts of a task one after the other; a drain does not hold its task, so a crashed service is replaced while another one is emptied); the memory of the rounds (cooldown, low load since, services being emptied, switched-off static services, failures). Registered as `ServiceListener`. |
| `ScalingSignals` | Player counts and node memory from the monitor and the proxies' player events; `merge` is the pure rule. |
| `ScalingTarget` | What the scaler starts, stops and empties: `ServiceManager.startDynamic(task)`, `start(task, index)`, `stop(id)` and `ChannelBridge.drain(id, timeout)`. |
| `ChannelBridge.drain` | Closes a backend on the proxies (`ServerCapacity`), sends `TransferPlayer` for each player on it (again after 2, 4 and every 8 s while the player is still there), waits until the proxies report it empty, and opens it again if not. |
| `TransferPlanner` | Where the players of an emptied backend go: the emptiest backend of the group, one player at a time. |

`CloudNode` builds it after the monitor and starts it once the configured services have started (also after a setup that finished later), suspends it while `setup` runs, and closes it before the services stop. The configured start runs once per run of the node: if `eula-accepted` or `setup-complete` is taken back and given again, the running scaler brings the services back on its own.

## Tests

| Test | Covers |
|---|---|
| `ScalingPlannerTest` | every rule with its boundary (39 and 40 of 50 at 80 %, 10 + 70 and 10 + 69 of 100 for the group, 24 and 25 of 50 below 50 %, 4:59 and 5:00 low load, 59 s and 60 s cooldown, memory exactly enough and 1 byte or 1 MB short, `max-memory` and the reserve with and without a database), unknown counts, starting and draining services, static services taking players, proxies, unwanted groups and other nodes, each with a counter-check |
| `ScalingSignalsTest` | `stale-after` to the second (a version without the check fails), `min(online, max)`, the lower count while a proxy watches and the bridge's count without one, node memory fresh, stale or not measured |
| `AutoscalerTest` | the rounds on a fake service core and clock: setup and EULA, suspend and close, low load reset by a rise, a drain that empties and one that does not (no stop, the time starts again, the log names timeout, no target or interrupt), a service being emptied never chosen twice, a crash during a drain replaced in the next round, released static services judged by the configuration at the stop, pause after three failures and its end by time or by a changed task file, crashes and the reset by a stable service, one start per task at a time, memory holds |
| `ChannelBridgeDrainTest` | `ChannelBridge.drain` on a fake service core, channel and clock: a newcomer during the poll gets a move of his own, a move that did not happen is sent again after 2, 4 and 8 s, the timeout, no target and an interrupt open the backend again, empty only when both lists say so |
| `TransferPlannerTest` | lowest load first, counting the players already sent, then fewer players, then name |
| `NodeWiringTest.theScalerFollowsThePlayerCountsOfTheBridgesAndNeverStopsAStaticServer` | the whole node with fake servers: a reported player starts `minigame-2`, low load stops it again, `minigame-1` and the static lobby stay, a lobby stopped by hand stays down |
| `NodeWiringTest.aSurplusServiceWithPlayersIsEmptiedThroughTheProxyBeforeItStops` | with a fake proxy: `minigame-2` is closed (`ServerCapacity open=false`), its player gets a `TransferPlayer` to `minigame-1`, the server runs until the proxy reports the move, then stops |
| `NodeWiringTest.aDeclinedEulaOrMissingAnswersStartNothingUntilTheSetupIsDone` | after the setup the servers start; the EULA taken back and given again brings back the same `lobby-1` |
| `NodeWiringTest.theGroupsOwnSwitchStopsItOnReloadAndSwitchingItOnBringsItBack` | a static service stopped by a switch comes back at its own index |
| `ServiceLifecycleIntegrationTest.aStaticServiceStartsAtAGivenIndexInItsOwnFolderWithinTheLimits` | `ServiceManager.start(task, index)`: range, folder, running index, `max-services`, unwanted group |
| `ServerLoadsTest`, `ProxyBridgeTest` (cloud-bridge-common) | the proxy's choice of the emptiest server, pending players, closed servers, transfers only inside the group to an open listed server, caught kicks to the group, then the lobby, closed servers refused except with `vectora.server.bypass-closed` |
| `KickClassifierTest`, `KickGuardTest` (cloud-bridge-common) | which kicks are caught, each server once per incident, at most 3 caught kicks in 60 s |

## Limits today

- One node. With the cluster (PR 20) each node scales its own tasks; a task on several nodes is not balanced between them yet.
- The proxies know the slots only of backends the node registered; servers listed by hand in `velocity.toml` are chosen the way Velocity would.
- Scaling starts from a running service: a task with `min-services: 0` and no service has no player count to scale on (no queue yet).
- The budget looks at the maximum heaps, not at what a JVM uses beyond its heap (metaspace, threads, native memory). Leave headroom in `heap.max`.
