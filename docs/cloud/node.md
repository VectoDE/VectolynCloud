# VectoraCloud node: service core

`VectoraCloud/cloud-node` (package `net.vectora.cloud.node`) is the node itself. This page covers the entry point the launcher calls, how the node puts its parts together (home lock, Java runtimes, database, cloud channel, console) and the service core, which renders the wanted servers, starts them as plain Java processes, watches them, restarts them after a crash and stops them in stages. The [setup assistant](setup.md) runs before the first service starts. Before each start the node downloads the server software and the plugins of the group and copies the bridge jar into the service ([Server software](#server-software)). Scaling comes in a later pull request; the interfaces left open are listed at the end.

## Start order

`NodeMain.start(Map<String, String>)` is called by the launcher (settings in [launcher.md](launcher.md#node-entry-point)). It:

1. checks the settings (`NodeSettings.parse`: `home` absolute, `language` `de` or `en`, booleans `true` or `false`);
2. takes the [home lock](#home-lock) and removes what an interrupted run left behind: unfinished downloads (`cache/artifacts/download-*.part`, `ArtifactCache.removeStalePartFiles`) and unfinished Java installs (`jre/.staging-*`, `jre/.old-*`, `JreInstaller.removeLeftovers`);
3. opens the configuration (`ConfigService`) and generates missing secrets in `secrets/vectora.env`;
4. builds the parts (`CloudNode.open`) and registers the shutdown hook `vectora-shutdown` before anything starts, so a `SIGTERM` or Ctrl+C while the database is set up or the servers start still stops what has been started: the service core with the [Java runtimes](#java-runtimes) and the [database](#database) as start gate and placeholder source, the [cloud channel](network.md) server with the tokens of the service core, and the [console](console.md);
5. writes every [built-in template](#built-in-templates) the home does not have yet, then starts watching the configuration files and reconciles on every change (see [Reload](#reload));
6. starts the cloud channel on host and port of this node in `nodes.yml`; services get the bound address in `VECTORA_CLOUD_HOST` and `VECTORA_CLOUD_PORT`;
7. starts the [monitoring](monitor.md) (`MonitorRuntime`, opened in step 4 with the node id as origin; an invalid `monitor.yml` fails the start there);
8. prepares the console: log records go through it with the secret filter (`logHandler(RedactingFilter::redact)`), the built-in commands get the real shutdown, and the [`service` commands](#console-commands), `setup`, `monitor`, `alerts`, `backup` and `api` are added (`api token` works while the API is off);
9. ends orphaned servers from the previous run (see [Orphans](#orphans)). Only then does it put right a [backup or restore](backup.md) that a crash interrupted (`recoverInterruptedOperations`), start the backup schedule and, with `api.enabled`, the [API](api.md) server, so neither a backup nor a start or restore through the API touches a folder that a server of the previous run still writes to. An API that cannot be prepared or bound is logged as an error and the node runs without it;
10. runs the setup: `SetupWizard.ensureComplete(console, config, SetupEnvironment.of(settings, env))`, also without a console (then only a setup file can answer). The launcher's `setup-file`, `non-interactive` and `platform.*` settings reach `SetupEnvironment` through `NodeSettings.toMap()`. A `SetupException` is printed with `describe(language)`, on the console or, without one, in the log. On `EULA_DECLINED`, `CANCELLED` or any other result that is not ready, no service starts; the console stays open, and once `setup` finishes there, the node starts the planned services right away;
11. starts the console and every planned service, but only if `cloud.yml` has `setup-complete: true` and `eula-accepted: true`. Otherwise it logs a warning and starts nothing. The same gate, and the database's start gate, apply to every later start, also `service start` in the console, `service restart` and the automatic restart after a crash;
12. keeps the JVM alive with the non-daemon thread `vectora-node`.

A second `start` in the same JVM is an error.

`CloudNode.close()` (the shutdown hook, `shutdown` in the console, a second Ctrl+C) runs the reverse order: first the API refuses starts, reloads and backups (`beginShutdown`), then console, backup schedule, services (each with its stop command), monitoring (after the last service: history file, queued notifications), database (`DatabaseProvisioner.shutdown()` after the last service), cloud channel, configuration, log handlers, the API server (event streams get `1001`), home lock. Every step runs in a `try`/`finally` of its own: if one fails (also with an `Error`), it is logged and the next step still runs, so the home lock is always released; an `Error` is rethrown at the end. It is idempotent; a second caller waits until the first has finished. Once it has begun, no start reaches the database any more, so a start that was still running cannot leave a MariaDB behind. `SIGTERM` (`docker stop`, systemd) and Ctrl+C in plain mode reach the JVM's shutdown hook and end the node the same way.

## Home lock

Only one cloud may use a home. The node holds an exclusive lock on `state/cloud.lock` (`FileChannel.tryLock`) for as long as it runs. A second cloud on the same home, in this or another process, stops at the start with `HomeLock.HomeLockedException`: "Another VectoraCloud already runs with the home … (it holds state/cloud.lock)". The operating system releases the lock when the process ends, also after a crash or `kill -9`, so a stale file never blocks a start. The cleanup of download and install leftovers relies on the lock: no other process can be writing them.

## Java runtimes

`ManagedJavaRuntimes` is the node's `JavaRuntimeResolver`. For each start it builds the group's `JavaRequirement` and asks `JreManager` (see [download.md](download.md)) for a runtime:

- PandaSpigot (the 1.8.8 groups) gets Java 21, every other software Java 25.
- `java: <major>` in the group file overrides that. An override below the minimum of the server software fails the start with a message that names both versions.
- The minimum is the highest known one (`JavaRequirement.minimumOf`): Mojang's `javaVersion`, Fill's `java.version.minimum`. `SoftwareInstaller` records it per group while it provides the server jar, which happens before the Java runtime is picked, and keeps it in `cache/java-minimums.properties` for offline starts.
- A system Java of exactly that version is used if there is one; otherwise a Temurin JRE is downloaded into `<home>/jre/` and pinned in `software.lock.yml`, the same `SoftwareLockStore.inHome(home)` the other downloads use.

The JRE manager is built on the first start that needs it.

## Server software

`software.SoftwareInstaller` is the node's `ServerSoftwareProvider`. A start goes: render the template, provide the software, check the bridge, pick the Java runtime, launch.

- The server jar, the group's plugins or mods and the bridge jar come from the [catalog](download.md#what-the-node-installs), pinned in `software.lock.yml` on the first start and replayed from `cache/artifacts/` afterwards.
- The bridge of the platform (`VELOCITY` and `VELOCITY_LEGACY`: Velocity bridge, `PAPER`: Paper bridge, `PANDASPIGOT`: legacy bridge) is copied from the node's resources into `plugins/`. Fabric has none yet.
- **A `PANDASPIGOT` service without `plugins/VectoraCloudBridgeLegacy-<version>.jar` does not start.** Its `server.properties` has `bungeecord: true`; without the bridge's token check it would trust every connection that claims to come from the legacy proxy. The check runs in `ServiceManager` itself, also when a test or a later module plugs in another `ServerSoftwareProvider`.
- A download that fails stops this start: the service fails with the reason, like any other error before the launch.
- Floodgate's `key.pem` is only written, and `VECTORA_FLOODGATE_KEY_B64` only needed, when the template ships Floodgate's folder (`plugins/floodgate/` or `config/floodgate/` on Fabric; `TemplateInstaller.runsFloodgate`).

## Built-in templates

`VectoraCloud.jar` carries the templates of every server of the network: the tracked files of `Server-Network/<Server>/template/` for `Proxy`, `Legacy-Proxy`, `Lobby-Server`, `Survival-Server`, `Creative-Server`, `Mod-Server`, `End-Server`, `Minigame-Server`, `Legacy-Server` and `Database`. The build task `:VectoraCloud:cloud-node:templateResources` copies them into the node's resources under `net/vectora/cloud/node/templates/` and lists each file with its SHA-256 in `templates.index`. Jars, `key.pem`, `*.key`, `*.secret`, keystores and hidden files never go in, even if a checkout has them in a template folder.

On every start `BuiltInTemplates.seed` writes the templates whose folder `<home>/<Server>/template/` does not exist. That is what makes `java -jar VectoraCloud.jar` in an empty folder work: the release, the Docker image and the Pterodactyl egg ship only the jar.

- A template folder that exists, also an empty one or a link, belongs to the operator. The cloud never overwrites, merges or updates it; to get the built-in version back, delete or rename the folder and start again.
- Each folder is written into `<home>/<Server>/.vectora-template-incoming/` first, every file is checked against the index, and the folder is then moved into place. A crash leaves either the whole template or none; the staging folder is removed on the next start.
- The index accepts only plain paths below `<Server>/template/` (no `..`, no hidden names, no backslash).
- A template that cannot be written is logged as an error; the node keeps running, and the servers of that template fail to start with the missing folder as reason.

In the repository checkout (`Server-Network/` as home) every template exists, so nothing is written.

## Database

`NodeDatabase` connects the [database module](database.md) to the service core:

- As `ServiceManager.StartGate` it calls `DatabaseProvisioner.provision()` before the first service starts, once per run. A failed attempt refuses that start with the reason and is repeated at the next start. The provisioner's `notice()` (for example that `max_connections` could not be read) is logged as a warning.
- As `PlaceholderContributor` it adds `VECTORA_DB_HOST`, `_PORT`, `_NAME`, `_USER`, `VECTORA_DB_STORAGE_METHOD` and `VECTORA_DB_POOL_SIZE`. The pool size comes from the provisioner's `ConnectionBudget`, shared by all services that may run on this node at once (the sum of `max-services` of the wanted tasks). If even pools of 2 do not fit, the start is refused with the budget's explanation instead of servers that fail later with "Too many connections".
- `shutdown()` runs after the last service has stopped. After it, `beforeStart()` refuses.
- Only a wanted database server runs: with `servers.database: false` in `cloud.yml`, the mode `managed` starts no MariaDB and uses the embedded H2 store instead, with a warning. The modes `docker` and `external` start no server of the cloud's own, and `h2` starts nothing.
- `NodeDatabaseProcesses` runs MariaDB's programs for the managed mode: absolute program path, no shell, output appended to the log file, the node's environment without `VECTORA_*`, and a program that does not finish in time is killed with its children.

`database.mode` is read when the database is first set up; a change takes effect at the next start of the cloud.

## Which services run

A service is one running server process. The plan comes from the configuration:

- Each task in `tasks/` that runs on this node (`nodes` empty or containing `node-id`) gets `min-services` services of its group, but only if the group is wanted (`ServicePlanner.isWanted(group, cloud)`):
  - `wanted: false` in the group file switches the group off, whatever its template (for example one minigame of several);
  - a group whose `template` is one of the nine known folders (`Proxy`, `Lobby-Server`, `Survival-Server`, `Creative-Server`, `Mod-Server`, `End-Server`, `Database`, `Minigame-Server`, `Legacy-Server`) also needs its switch under `servers` in `cloud.yml`; every PandaSpigot group, also on a template of its own, follows `servers.legacy-server`;
  - the 1.8.8 groups (the `Legacy-Proxy` template, the `Legacy-Server` template and every PandaSpigot group) are only wanted while `legacy-proxy.enabled` is `true`;
  - `Database` is never a service; the [database module](database.md) runs it;
  - any other group on a template folder of its own counts as wanted.
- An unwanted group is neither rendered nor started, `service start` refuses it and names the setting that switches it off, and it appears in no proxy configuration: neither in the `velocity.toml` of a proxy that renders, nor in a `RegisterServer` of the bridges.

| Group | `wanted` | Switch in `servers` | `legacy-proxy.enabled` | Runs |
|---|---|---|---|---|
| `lobby` (`Lobby-Server`) | missing or `true` | `lobby: true` | any | yes |
| `lobby` | `false` | `lobby: true` | any | no |
| `lobby` | missing | `lobby: false` | any | no |
| `bedwars` (`Minigame-Server`) | `false` | `minigame: true` | any | no, the other minigames run |
| `legacy` (PandaSpigot, own template) | missing | `legacy-server: true` | `true` | yes |
| `legacy` | missing | `legacy-server: true` | `false` | no |
| `legacy` | missing | `legacy-server: false` | `true` | no |
| legacy proxy (`Legacy-Proxy`) | missing | none | `false` | no |
| `custom` (own template, Paper) | missing | none | any | yes, by its task |
| `custom` | `false` | none | any | no |
- `max-services` caps the services per task, also for manual starts.

Backends start before proxies, so the proxy's server list is complete when it renders.

## Ids, folders and ports

| | Rule |
|---|---|
| Service id | `<group>-<index>`, index 1 to 9999, the lowest free index of the group. Example: `lobby-1`. |
| Folder, static task | index 1: `<home>/<template>/runtime`, index n: `<home>/<template>/runtime-<n>`. Kept after the stop: worlds and player data stay. |
| Folder, dynamic task | `<home>/temp/services/<id>`. Cleared before the start and deleted after the stop. Only direct children of `temp/services` are deleted, and links are not followed. While the folder of a stopped service is still being deleted (on Windows a locked file can delay this by a few seconds), its id is not handed out again; the next start takes the next free index. |
| Port, proxy | `cloud.proxy.port` (the software `velocity` marks a proxy); the proxy on the `Legacy-Proxy` template gets `legacy-proxy.port`. One proxy of each kind per node. |
| Port, other services | the first free port in the node's `service-ports`, skipping the node's fixed ports (proxy, Bedrock, legacy proxy, API, database, channel) and ports another program already has bound. Never two services on one port. |

Every start renders the template with [cloud-template](template.md) into the folder. Secrets are need-to-know: a template only gets the secrets of `secrets/vectora.env` that its platform uses (`ServiceSecrets`):

| Platform | Secret placeholders |
|---|---|
| `VELOCITY` (main proxy) | `VECTORA_FORWARDING_SECRET`, `VECTORA_FLOODGATE_KEY_B64`, `VECTORA_DB_PASSWORD` |
| `VELOCITY_LEGACY` (legacy proxy) | `VECTORA_LEGACY_FORWARDING_SECRET` only |
| `PAPER`, `FABRIC` | `VECTORA_FORWARDING_SECRET`, `VECTORA_MANAGEMENT_SECRET`, `VECTORA_DB_PASSWORD`, `VECTORA_FLOODGATE_KEY_B64` |
| `PANDASPIGOT` | none; its bridge gets `VECTORA_LEGACY_FORWARDING_SECRET` in the environment |
| `NONE` (database template) | `VECTORA_DB_PASSWORD` |

`VECTORA_DB_ROOT_PASSWORD` reaches no template; only the database module uses it. A template that asks for a secret outside its row fails to render (unknown placeholder). If a secret of the row is missing in `vectora.env`, the start fails with "secrets/vectora.env has no value for ..., which every ... service needs"; the legacy proxy also refuses a legacy secret equal to the modern one. Other `VECTORA_*` keys an operator adds to `vectora.env` are no placeholders. Besides the secrets, the node provides `VECTORA_SERVER_HOST`, `VECTORA_SERVER_PORT`, `VECTORA_XMS`, `VECTORA_XMX`, `VECTORA_DB_HOST`, `VECTORA_DB_PORT`, `VECTORA_DB_NAME`, `VECTORA_DB_USER` and, for the proxy, `VECTORA_PROXY_BIND`, `VECTORA_PROXY_PORT`, `VECTORA_BEDROCK_PORT` and the server list. In the proxy list a static backend with index 1 appears under its group name (`lobby`), every other one under its id (`lobby-2`); only backends that are still wanted and not being stopped are listed. The try list holds the lobby backends. The main proxy lists every backend except PandaSpigot; the legacy proxy lists only the PandaSpigot backends, all of them in its try list, and gets `VECTORA_LEGACY_PROXY_PORT`. Dynamic services are not written into `velocity.toml`; they register through the bridge (PR 13).

## Process

The node starts the server JVM directly with `ProcessBuilder`, without a wrapper jar (see [architecture](architecture.md#no-wrapper-jar)):

```
<java> -Xms<heap.min> -Xmx<heap.max> <process.jvm-flags> [-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8] -jar <software jar> [--nogui | nogui]
```

- `<java>` comes from the `JavaRuntimeResolver`, in the running node `ManagedJavaRuntimes` (see [Java runtimes](#java-runtimes)).
- The template is installed for its platform: `Legacy-Proxy` with Velocity as `VELOCITY_LEGACY`, so its `forwarding.secret` holds `VECTORA_LEGACY_FORWARDING_SECRET`; PandaSpigot as `PANDASPIGOT`; the others by their software.
- The encoding flags are added from Java 18 on. Paper and Fabric get `--nogui`, PandaSpigot the bare word `nogui` (CraftBukkit 1.8 treats `--nogui` as an unknown option, prints its help and exits with 0), Velocity nothing.
- No shell is involved; flags are single arguments. Flags that would change heap, class path or the main jar (`-Xms…`, `-Xmx…`, `-jar`, `-cp`, `-XX:Flags=`, `-XX:VMOptionsFile=`, `-version`, `-help`, …) are rejected by the validator.
- The environment is the node's own without any `VECTORA_*` variable, plus the variables below. Their names are constants of `net.vectora.protocol.ServiceEnvironment`, which `CloudClientSettings` reads as well, so node and bridges cannot drift apart.

| Variable | Value |
|---|---|
| `VECTORA_SERVICE_ID` | service id |
| `VECTORA_SERVICE_GROUP`, `VECTORA_SERVICE_TASK` | group and task name |
| `VECTORA_SERVICE_PORT` | the service's port |
| `VECTORA_SERVICE_TOKEN` | a new random token per start (`ServiceTokens.issue`, 32 bytes, base64url). The bridge logs in to the cloud channel with it; the channel checks it in constant time through `ServiceManager.authenticator()`. The service core is the only place that issues and removes tokens: `ServiceTokens.remove(id, token)` when the process ends and when the start fails. It only removes the token of that start, so a late exit of an old process cannot remove the token of the restart that follows. |
| `VECTORA_SERVICE_PLATFORM` | the `ServerPlatform` name (`VELOCITY`, `VELOCITY_LEGACY`, `PAPER`, `FABRIC`, `PANDASPIGOT`), so the bridge knows what it runs on |
| `VECTORA_LEGACY_FORWARDING_SECRET` | only for `PANDASPIGOT`: the `bungeeguard` token its bridge checks. No other service gets it in its environment. |
| `VECTORA_CLOUD_HOST`, `VECTORA_CLOUD_PORT` | where this node's channel listens, as bound. A wildcard bind (`0.0.0.0`, `::`) is passed as `127.0.0.1` or `::1`. |

## Life cycle

```mermaid
stateDiagram-v2
    [*] --> PREPARED
    PREPARED --> STARTING
    STARTING --> RUNNING: "Done (…)!" or bridge
    STARTING --> STOPPING
    RUNNING --> STOPPING
    STOPPING --> STOPPED
    STARTING --> CRASHED
    RUNNING --> CRASHED
    PREPARED --> CRASHED: render or start failed
    CRASHED --> PREPARED: restart
    CRASHED --> STOPPED
    STOPPED --> PREPARED
```

- **RUNNING** is reached when the output contains a line with `Done (` and `)!` (Paper, Fabric and Velocity print it) or when the bridge sends `ServiceReady` on the cloud channel (`CloudClient.markReady()`; `ChannelBridge` calls `ServiceManager.markReady`).
- **Stop while PREPARED** (template being rendered, software being downloaded): the service ends in STOPPED right away and no process is started.
- **The channel follows the life cycle:** when a process ends (STOPPED, CRASHED) its token is removed and `ChannelBridge` closes its connection with `SHUTDOWN`. The last `ServiceStatus` of each service is kept for `service list` and `service info`.
- **Proxies learn about backends from the bridge:** when a backend reaches RUNNING, `ChannelBridge` sends `RegisterServer(name, host, port, group, legacy)` to the proxy that lists it: PandaSpigot backends (`legacy=true`) to the legacy proxy, every other one to the main proxy. The name is the one in `velocity.toml` (`lobby` for `lobby-1`). STOPPING, STOPPED and CRASHED send `UnregisterServer`. A proxy that connects (or reaches RUNNING) gets every current backend of its kind. Unwanted backends are never registered, and a backend that becomes unwanted on a reload is unregistered when it stops.
- **Players:** `PlayerJoin`, `PlayerSwitch` and `PlayerQuit` from a proxy are kept in `ChannelBridge.players()` (`NetworkPlayers`). The proxy names a server as in its list; the node maps that name back to the service (`lobby` to `lobby-1`). Events from a service that is no proxy, for an unknown server or that do not fit the known state are dropped and logged at debug level without their content. When a proxy ends, its players are removed.
- **Stop** goes in stages: the stop command on standard input (`stop`, for Velocity `shutdown`, or `process.stop-command`), then up to `process.stop-timeout` (default 60 s), then a normal termination request, 10 s later the forced end of the process and its children. On Windows the termination request already ends the process.
- **Exit:** a requested stop or exit code 0 ends in STOPPED, anything else in CRASHED.
- **Restart after a crash** (`process.restart`): with `on-crash: true` the service is prepared and started again after `backoff`, doubled for each further crash and capped at `max-backoff`. After `max-attempts` crashes in a row it stays CRASHED. A service that ran for 5 minutes starts counting from zero again.

Listeners (`node.service.ServiceListener`; the cloud channel's listener is `ChannelListener`) get every state change and every output line and implement both methods (an empty body is fine). State changes arrive in order on one shared thread; output lines arrive in order on the output thread of each service.

## Configuration

Each group can have a `process` section; all of it is optional. Details in [config.md](config.md#groupsnameyml).

```yaml
process:
  jvm-flags: [-XX:+UseG1GC]
  stop-command: stop
  stop-timeout: 60s
  restart:
    on-crash: true
    max-attempts: 5
    backoff: 5s
    max-backoff: 300s
```

## Reload

When a configuration file changes, the node calls `ServiceManager.stopUnwanted()`: services whose group is now unwanted (`wanted: false` in the group file, `servers` switch off, `legacy-proxy.enabled` off) are stopped cleanly with their stop command, in the background, and are not planned again. Their proxies get `UnregisterServer`, also for a static backend that stands in `velocity.toml`: the Velocity bridge then withdraws it from the running server list, so the proxy no longer offers it (see [bridges.md](bridges.md#dynamic-servers)). The line in the rendered `velocity.toml` goes away with the proxy's next start. A proxy whose bridge was not connected at that moment gets the `UnregisterServer` when it connects again: the node remembers what each proxy was sent and withdraws what has ended in the meantime, before it sends the current backends. New or changed tasks are not started automatically yet (scaling, PR 15); `service start <task>` starts one.

Two changes are special:

- **`eula-accepted` taken back** (by hand or by `setup`): every running server stops, since none may run without the operator's consent. Nothing starts until it is set again; a start that was still waiting at that moment (for the database or a download) reads `cloud.yml` again right before its process would start and is refused.
- **While `setup` runs** in the console, nothing is reconciled: it saves in several steps and rolls them back if a later one fails. When it ends, the node compares the configuration with the one before the run and reconciles once; a rolled-back run changes nothing.

Whether a group runs is one rule, `GroupRules.isWanted` in cloud-config, used for planning, starts, the proxy lists, this reload and `runs` in the API.

## Console commands

The node registers `service` (alias `services`) in the console, next to the [built-in commands](console.md):

| Command | Does |
|---|---|
| `service list` | every service with state, port, pid and players (from the last status report) |
| `service info <id>` | all details of one service, with its last status report |
| `service start <task>` | starts one more service of a task (same gate and limits as every start) |
| `service stop <id>` | stops a service and waits until it has ended |
| `service restart <id>` | stops it and starts it again |
| `service command <id> <line>` (alias `cmd`) | types a line into the server's console |
| `service screen <id>` (alias `logs`) | shows the server's output in the console; the same command again hides it |

Lines shown by `screen` have passed the secret filter; control characters and Unicode direction marks are replaced with `?`, so a server cannot send escape sequences to the operator's terminal.

## Logs

The output of each service (standard output and standard error together) goes to `<home>/logs/services/<id>.log`. The previous five runs are kept as `<id>.1.log` to `<id>.5.log`. Every line is passed through the secret filter first: values from `vectora.env` and the service token are written as `***`, in the log and to listeners. Lines longer than 8192 bytes are split, invalid UTF-8 is replaced.

## `state/services.json`

On every state change the node writes all services to `<home>/state/services.json`, atomically (temporary file, flush, rename). Example:

```json
{
  "schema-version" : 1,
  "node" : "node-1",
  "services" : [ {
    "id" : "lobby-1",
    "group" : "lobby",
    "task" : "lobby",
    "index" : 1,
    "mode" : "static",
    "state" : "running",
    "pid" : 4242,
    "started-at" : 1700000000000,
    "port" : 30001,
    "work-directory" : "Lobby-Server/runtime"
  } ]
}
```

The file is read strictly: unknown fields, duplicate keys, an id that is not `<group>-<index>`, negative numbers, ports outside 1 to 65535 and folders that are not plain relative paths are errors. A broken file is moved aside as `services.json.broken-<millis>` and the node starts with an empty state. `ServiceStateStoreFuzzTest` fuzzes the reader.

## Orphans

If the node ends without stopping its services (crash, power loss, `kill -9`), the servers may still run. At the next start the node reads `state/services.json` and, for each service with a pid:

- ends the process if the pid exists and its start time matches the recorded one (within one second): a termination request, then the forced end after the group's `stop-timeout`;
- leaves it alone if the start time differs: the pid now belongs to another program;
- blocks the folder and the port for this run if the start time cannot be read, so no second server opens the same world. The id is reported as unverifiable.

Afterwards the leftover folders in `temp/services/` are deleted, except blocked ones.

## End-to-end test

`CloudJarEndToEndTest` starts the real `VectoraCloud.jar` as its own process, the way an operator does:

```sh
./gradlew :VectoraCloud:cloud-node:e2eTest                          # offline, fake servers (CI: Integration job)
./gradlew :VectoraCloud:cloud-node:e2eTest -Pe2eDatabase=managed    # same with a downloaded MariaDB (managed-mariadb.yml)
./gradlew :VectoraCloud:cloud-node:e2eLiveTest                      # real downloads and servers (cloud-e2e-live.yml, weekly)
```

Add `-Pe2eAllowRoot=true` in a shell that runs as administrator or root; the test then passes `--allow-root`. GitHub's Windows runners run as administrator, so `managed-mariadb.yml` sets it there.

**Offline (`e2eTest`).** The home starts empty except for `nodes.yml` (a free channel port) and a pre-seeded `software.lock.yml` with `cache/artifacts/`: every catalog entry the default groups ask for points at one fake server jar that logs in to the cloud channel like a bridge. The cloud runs on Java 25 and sees exactly a Java 21 and a Java 25 on the `PATH`. It gets `--non-interactive --setup-file` with every server switched on (proxy, lobby, Survival, Creative, Mod-Server, End, minigames, legacy proxy, 1.8.8 server), free ports, H2 and 256M per group. The test checks:

- the setup ran from the file and the built-in templates were written;
- all nine services become `running`, which only the channel's `ServiceReady` does;
- `software.lock.yml` is unchanged byte for byte, nothing was downloaded, no `.part` file is left;
- each server's `.vectora-managed` lists exactly the catalog's plugins (or mods) for its template plus the bridge of its platform, and none on Fabric; the Floodgate key is in the lobby but not in the 1.8.8 server;
- the 1.8.8 server runs on Java 21, Paper and Velocity on Java 25;
- the main proxy gets a `RegisterServer` for every modern backend and none for the 1.8.8 server; the legacy proxy gets the 1.8.8 server with `legacy=true` and not the lobby;
- `shutdown` on the console stops every server with its stop command and the cloud ends with exit code 0.

It takes about 15 seconds. Removing the template seeding makes it fail: no server can render.

**Managed MariaDB.** With `-Pe2eDatabase=managed` the setup chooses `managed`, so the node downloads MariaDB, sets it up before the first server and stops it after the last one; every seeded lock entry stays as it was and only MariaDB is added. It needs internet access and runs in `managed-mariadb.yml` on Linux and Windows; downloading and initialising MariaDB takes a few minutes on a runner, so the test waits up to 15 minutes in this mode.

**Live (`e2eLiveTest`).** No seeding, no fake: the proxy with the lobby (Paper 26.3) and the legacy proxy with the 1.8.8 server (PandaSpigot), with the real downloads and Java 21 and 25. It checks that the jars are in place and pinned, that each server's bridge reports ready on the channel, that each proxy's bridge logs its backend (`Server … from VectoraCloud`) and the clean shutdown. With a fast connection it takes about a minute. It depends on the download APIs and needs a few GB of memory, so it runs weekly and on demand (`cloud-e2e-live.yml`), not on every pull request. Its first run found that PandaSpigot exits on `--nogui` (see [Process](#process)).

What none of these tests do: join with a real client. Logging in with Java 1.8.8 to 26.3 and with Bedrock stays a manual check.

## Interfaces for the next pull requests

| Interface | Default in the running node | Who plugs in |
|---|---|---|
| `CloudNode.open(settings, options -> …)` with `CloudNode.Options`: `services()` (the `ServiceManager.Builder`), `network(…)`, `database(…)`, `console(…)`, `noConsole()` | real parts | modules that replace one of them, tests |
| `CloudNode.console()` (`Optional<Console>`; `Console.prompter()` asks questions), `config()`, `secrets()`, `services()`, `database()`, `network()`, `channel()` | | PR 11 (setup assistant), PR 13 |
| `NodeSettings.nonInteractive()`, `setupFile()`, `toMap()` | from the launcher; `toMap()` feeds `SetupEnvironment.of` | |
| `CloudNode.Options.environment(Map)` | `System.getenv()` | tests (no Pterodactyl or Docker detection) |
| `ServiceManager.registration(id)`, `registrations(legacy)`, `runningProxies(legacy)`, `proxyKind(id)`, `backendNamed(proxy, name)` | used by `ChannelBridge` | PR 13, PR 15 |
| `service.JavaRuntimeResolver` | `ManagedJavaRuntimes` on `JreManager`; `Minimums.of(groupName, group)` from `SoftwareInstaller` | |
| `service.ServerSoftwareProvider` (`provide(spec, folder)` returns the jar name) | `SoftwareInstaller` (catalog, lockfile, bridge); `existingJar()` for tests | |
| `service.PlaceholderContributor` (extra placeholder values; the node's own values win) | `NodeDatabase` | PR 24 (legacy proxy) |
| `ServiceManager.StartGate` (`beforeStart()`, before every start) | `NodeDatabase` | |
| `ServiceManager.addListener(ServiceListener)` | `ChannelBridge`, `ServiceCommands` | PR 11 (monitoring) |
| `ServiceManager.sendCommand(id, line)` | 1 to 1024 characters, no control characters | |
| `ServiceManager.start(task)`, `stop(id)`, `restart(id)`, `stopUnwanted()`, `services()`, `find(id)` | | PR 15 (scaling) |
| `ServiceManager.authenticator()`, `markReady(id)` | used by the cloud channel | PR 13, bridges |

## Limits today

- The Paper bridge is compiled against Paper 26.3. A Paper group on another Minecraft version gets the same bridge; whether it loads there is not tested.
- Updates of the server software or a plugin are not automatic: a pinned entry stays until it is removed from `software.lock.yml`.
- `service-ports` and `node-id` are read once when the node starts; a change needs a restart.
- One proxy and one legacy proxy per node. PR 24 completes the legacy proxy.
- New tasks and a higher `min-services` after a reload start only with `service start` or the next start of the node (PR 15).
- No scaling: tasks run exactly `min-services` services. PR 15 adds scaling.
