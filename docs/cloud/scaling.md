# VectoraCloud autoscaling

Package `net.vectora.cloud.node.scaling` in `VectoraCloud/cloud-node`. The node keeps `min-services` of every task running and adds or removes dynamic services by player count. German version: [scaling.de.md](scaling.de.md).

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
  scale-up-at: 75            # percent of players-per-service
  scale-down-below: 50       # percent; stored and checked, used by a later change
  scale-down-after: 5m       # empty this long before a surplus service stops
  cooldown: 60s              # at least this long between two decisions of the task
nodes: []
```

### Static and dynamic in one task

`static ≤ min ≤ max` must hold, and `scale-down-below` must be lower than `scale-up-at`.

- **Static services** (`static-services`): the first ones of the task. Each has its own folder under the group's template (`runtime`, `runtime-2`, …) that survives every stop, and the scaler never stops one.
- **Dynamic services**: everything above `static-services`, up to `max-services`. Each runs in `temp/services/<id>`, and that folder is deleted when the service stops.

A new service is static as long as the task has fewer static services than `static-services`, otherwise dynamic. The lobby above therefore has one static lobby and up to three dynamic ones. `static-services = max-services` is a purely static task (it never scales), `static-services = 0` a purely dynamic one.

Lowering `static-services` later does not turn existing static services into dynamic ones: they keep their folder and still count. Raising it makes the next new service static.

Suggested values from the setup (static/min/max): proxy and legacy proxy 1/1/1, survival, creative, mod and end 1/1/1, lobby 1/1/4, legacy 1.8.8 server 1/1/3, minigame 0/1/8; `scale-up-at` 75, `scale-down-below` 50.

The load rules for `scale-down-below`, the distribution across nodes and moving players follow in a second change; until then the rules below apply unchanged.

The file, the console and the API all change it through `ConfigService.apply` and the same validator:

| Way | Example |
|---|---|
| File | edit `tasks/minigame.yml`; the node reloads it after 200 ms |
| Console | `task set minigame max-services 6`, `task set lobby static-services 2`, `task set minigame scale-down-below 40` |
| API | `PUT /api/v1/tasks/minigame` with the whole task as JSON (scope `config:write`) |

The scaler reads the configuration again in every round, so all three take effect the same way, at the latest after one round (5 seconds). An invalid change is refused with the same errors everywhere and changes nothing.

## Which tasks are scaled

Only tasks of this node (`nodes` empty or naming this node) whose group is wanted. "Wanted" is the one rule `GroupRules.isWanted` in cloud-config that also decides planning, starts, the proxy lists and `runs` in the API: the group's own `wanted`, its switch under `servers:` in `cloud.yml`, and `legacy-proxy.enabled` for the 1.8.8 groups (see [node.md](node.md#which-services-run)). A group that is switched off is never scaled; its services stop on the reload as before.

Nothing is scaled before the setup is complete and the EULA accepted, while the `setup` command saves its steps, and while the node shuts down.

## Rules

Every 5 seconds the scaler looks at each task once and makes at most one decision for it. After a start or a stop the task waits `cooldown` before the next one.

1. **min-services.** If fewer services count than `min-services`, one more starts.
   - A dynamic service counts while it is prepared, starting, running, or crashed and waiting for its restart.
   - A static service always counts, also when it is stopped or gave up after its crashes. A server stopped with `service stop` therefore stays down. Only a static service that stopped because its group was switched off (or because `eula-accepted` or `setup-complete` was taken back) comes back once the switch is on again, at its own index and in its own folder.
   - A new static slot (a new task, a higher `static-services`) gets the lowest index of the group that no service has used in this run, so a folder of a server stopped by hand is not touched.
2. **Above max-services.** If a task has more services than `max-services` (the limit was lowered), an empty dynamic service stops, without waiting for `scale-down-after`. A service with players is never stopped.
3. **Scale up.** One more dynamic service starts when every running service of the task has reached `scale-up-at` percent of `players-per-service`, no service of the task is still starting, `max-services` leaves room, and the memory budget allows it (below). With 50 players per service and 80 %, that is 40 players on each.
4. **Scale down.** A dynamic service stops when it has had no players for `scale-down-after`, the task has more than `min-services`, and another service of the task still has room below the threshold or is starting. Without that last condition, stopping it would start a new one at once (hysteresis). Of several candidates the one with the highest index goes.

What never happens:

- A static service is never stopped by the scaler, also not after `static-services` was lowered.
- A proxy (`software: velocity`) is never scaled up or down; there is one proxy per node.
- A service with players is never stopped. Players are not moved to another server first; the scaler waits until the server is empty. Moving players needs a message the cloud channel does not have yet.
- `min-services: 0` starts nothing. The first service of such a task comes from `service start`; from then on it is scaled.

A dynamic service stopped with `service stop` is replaced if the task falls below `min-services`, because dynamic services are a pool. To take the dynamic part of a task down, set `min-services` to `static-services`, switch its group off, or remove the task.

## Signals

| What | Source |
|---|---|
| Players per service | The newest sample of the [monitor](monitor.md): `ServiceStatus.playersOnline` that the service's bridge reports every 5 seconds. A sample older than `stale-after` (60 s) is not used. On top, the players the proxies report on that service (`PlayerJoin`, `PlayerSwitch`, see [bridges.md](bridges.md#player-events)); the higher of the two counts. |
| Memory of the node | The newest node sample of the monitor (`node-memory`: used and total memory of the machine, or of the container where Java sees a limit). |

A count that is not known (no report yet, an outdated one, a server without bridge) is never full and never empty. Such a service blocks a scale-up of its task and is not stopped. With `enabled: false` in `monitor.yml` there are no samples: the scaler then keeps `min-services` and does not scale up or down.

## Memory budget

A scale-up (rule 3) also needs:

- the maximum heaps (`heap.max`) of every service on the node that has or is about to get a process, plus the new one, to fit into the memory of the node;
- at least the new service's `heap.max` to be free right now.

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
Scaling: minigame-3 of task minigame is started (every running service has reached 80 % of 50 players)
Scaling: minigame-3 of task minigame is stopped (empty for 5m, another service has room)
```

## Ids, folders and proxies

A scaled service is an ordinary service of the service core ([node.md](node.md#ids-folders-and-ports)): id `<group>-<index>` with the lowest free index, dynamic folders in `temp/services/<id>`, a port from the node's `service-ports`, and the proxies learn about it through `RegisterServer` once it is running and through `UnregisterServer` when it stops.

## Code

| Class | Does |
|---|---|
| `ScalingPlanner` | The rules above as a pure function of configuration, services, signals and what earlier rounds remember. No I/O, no clock. |
| `Autoscaler` | The rounds on the thread `vectora-scaling`, starts and stops on `vectora-scaling-action` (at most one per task at a time), the memory of the rounds (cooldown, empty since, switched-off static services, failures). Registered as `ServiceListener`. |
| `ScalingSignals` | Player counts and node memory from the monitor and the proxies' player events. |
| `ScalingTarget` | What the scaler starts and stops: `ServiceManager.start(task)`, `ServiceManager.start(task, index)` and `stop(id)`. |

`CloudNode` builds it after the monitor and starts it once the configured services have started (also after a setup that finished later), suspends it while `setup` runs, and closes it before the services stop.

## Tests

| Test | Covers |
|---|---|
| `ScalingPlannerTest` | every rule with its boundary (39 and 40 players at 80 % of 50, 4:59 and 5:00 empty, 59 s and 60 s cooldown, memory exactly enough and 1 MB short), unknown counts, starting services, hysteresis, static services and proxies, unwanted groups and other nodes, each with a counter-check |
| `AutoscalerTest` | the rounds on a fake service core and clock: setup and EULA, suspend and close, empty time reset by a join, pause after three failures and its end by time or by a changed task file, crashes and the reset by a stable service, switched-off static services, one start per task at a time, memory holds |
| `NodeWiringTest.theScalerFollowsThePlayerCountsOfTheBridgesAndNeverStopsAStaticServer` | the whole node with fake servers: a reported player starts `minigame-2`, empty servers stop it again, `minigame-1` and the static lobby stay, a lobby stopped by hand stays down |
| `NodeWiringTest.theGroupsOwnSwitchStopsItOnReloadAndSwitchingItOnBringsItBack` | a static service stopped by a switch comes back at its own index |
| `ServiceLifecycleIntegrationTest.aStaticServiceStartsAtAGivenIndexInItsOwnFolderWithinTheLimits` | `ServiceManager.start(task, index)`: range, folder, running index, `max-services`, unwanted group |

## Limits today

- One node. With the cluster (PR 20) each node scales its own tasks; a task on several nodes is not balanced between them yet.
- Players are not moved before a scale-down; the scaler waits until the server is empty.
- Scaling starts from a running service: a task with `min-services: 0` and no service has no player count to scale on (no queue yet).
- The budget looks at the maximum heaps, not at what a JVM uses beyond its heap (metaspace, threads, native memory). Leave headroom in `heap.max`.
