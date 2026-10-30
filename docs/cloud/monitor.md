# VectoraCloud monitoring

Module `cloud/monitor`, package `net.vectora.cloud.monitor`. It samples every service and the node at a fixed interval, keeps a history, evaluates alert rules with hysteresis and debounce, and reports alerts to the log, to listeners and, if the operator switches it on, to a webhook. An optional Prometheus endpoint exposes the same values on loopback.

Nothing is sent anywhere by default. Notifications and the Prometheus endpoint are off until the operator enables them in `monitor.yml` and enters the webhook URL in `secrets/vectora.env`.

The module does not depend on `cloud/node`. The node plugs it in through a small interface and two calls (see [Hooking it into the node](#hooking-it-into-the-node)); `cloud/node` does that in `CloudNode` with `ops.NodeMonitoring` (service list and state changes) and the channel's status reports, and registers `monitor` and `alerts`. The origin of notifications is the node id; `MonitorRuntime.open` refuses anything that is not a valid node id.

## Metrics

| Metric (rule id) | Unit | Source | Available today |
|---|---|---|---|
| `tps` | ticks per second | `ServiceStatus.tps` from the bridge | yes: the Paper and legacy bridges report it every 100 ticks (5 s at 20 TPS); the Velocity value is ignored on proxies |
| `mspt` | milliseconds per tick | `ServiceStatus.mspt` from the bridge | same as `tps` |
| `heap` | percent of the maximum heap | `ServiceStatus.heapUsed` / `heapMax` | same; missing when `heapMax` is `-1` (JVM without limit) |
| `heap-used` | bytes | `ServiceStatus.heapUsed` | same |
| `players` | count | `ServiceStatus.playersOnline` (and `maxPlayers` for display) | same |
| `cpu` | percent of the whole machine (all cores = 100) | `ProcessHandle.Info.totalCpuDuration()` between two samples | yes, from the node, no bridge needed |
| `rss` | bytes (working set on Windows) | `/proc/<pid>/status` (Linux), `tasklist` (Windows), `ps` (macOS, BSD) | yes, from the node |
| `restarts` | count | `restartAttempts` of the service core | yes, through `ServiceDirectory` |
| `crashes` | count within a window | state changes to `CRASHED` | yes, through `serviceStateChanged` |
| `node-cpu` | percent | `com.sun.management.OperatingSystemMXBean.getCpuLoad()` | yes |
| `node-memory` | percent used | MXBean total and free; on Linux without a container limit `MemAvailable` from `/proc/meminfo` | yes |
| `disk-free` | bytes | `FileStore.getUsableSpace()` of the home | yes |

TPS and MSPT are never taken from a proxy: Velocity has no tick loop and the bridge reports a fixed 20. The node says which services are proxies (`MonitoredService.proxy`).

A value that cannot be measured is shown as `-` and counts as missing for the rules. Nothing is refused on any operating system: where no source for `rss` exists, only `rss` is missing.

### Protocol: no change needed

`ServiceStatus` (protocol 1.0, message `0x11`) already carries players online and maximum, TPS, MSPT, heap used and heap maximum (`-1` for unknown). Everything the monitor needs from a server is in it, so `common/protocol` and `common/client` stay as they are. CPU and memory of the process come from the node, which knows the pid.

### What the bridges report, and what the monitor expects

The bridges (`plugins/bridge/paper`, `plugins/bridge/legacy`, `plugins/bridge/velocity`) already do all of this; the list is the contract for any later client such as the Vectora plugin.

- `CloudClient.reportStatus(online, max, tps, mspt)` at least once per `sample-interval` (default 10 s). The bridges report every 5 s. A report older than `stale-after` (default 60 s) counts as missing.
- Paper and PandaSpigot report on the main thread every 100 ticks. On a lagging server that takes longer than 5 s: at 3.3 TPS it is 30 s, which is why `stale-after` defaults to 60 s (covers down to about 1.7 TPS). A server that hardly ticks at all stops reporting; its TPS then counts as missing. A `low-tps` alert that already fires stays active, because missing values never clear an alert.
- `tps` is the one-minute average (`Bukkit.getTPS()[0]` on Paper, the bridge's `TickMeter` on the legacy fork), `mspt` the average tick time.
- Velocity: players online and the player limit. TPS and MSPT are fixed at 20 and 0; the monitor ignores them on proxies.
- Heap: nothing to do, `CloudClient` fills it in. `heapMax` is `-1` when the JVM has no limit; then the `heap` rule does not apply to that server.
- A new process must not send the last report of the previous one. `CloudClient` starts empty, so that holds already; the monitor also drops the stored report when a service stops or crashes.

## Configuration: `monitor.yml`

(File names in this section are relative to `config/`; the history file `state/monitor-history.bin` is not.)

The file is `config/monitor.yml` in the cloud home, next to `cloud.yml`. Without it the defaults below apply. It is read with the same strict YAML reader as the other configuration files: unknown fields, duplicate keys and type guessing fail, and errors name the file, the field path and the rule, never the value.

```yaml
schema-version: 1
enabled: true              # sample at all
sample-interval: 10s       # 1s to 5m
stale-after: 60s           # a status report older than this counts as missing; sample-interval to 1h
history:
  samples: 360             # per service and for the node, 10 to 100000 (360 x 10 s = 1 hour)
  persist: false           # also keep it in state/monitor-history.bin
  retention: 24h           # samples older than this are dropped from the file, 1m to 7d
  persist-interval: 5m     # how often the file is written, 1m to 1h; always on shutdown
rules:                     # missing: the default rules below; []: no rules
  - name: low-tps
    when: tps < 18 for 60s clear at 19 after 30s
    severity: warning      # warning (default) or critical
    groups: []             # only these groups; empty for all
    enabled: true
notifications:
  webhook:
    enabled: false
    url-secret: VECTORA_MONITOR_WEBHOOK_URL   # key in secrets/vectora.env
    format: discord        # discord, slack or json
    timeout: 10s           # 1s to 60s
    send-resolved: true
    min-severity: warning
prometheus:
  enabled: false
  bind: 127.0.0.1          # loopback only: 127.x.x.x, ::1 or localhost
  port: 9464
```

### Default rules

| Name | Rule | Severity |
|---|---|---|
| `low-tps` | `tps < 18 for 60s clear at 19 after 30s` | warning |
| `high-heap` | `heap > 90% for 60s clear at 85%` | warning |
| `low-disk` | `disk-free < 5GB clear at 6GB` | critical |
| `crash-loop` | `crashes >= 3 within 10m` | critical |
| `high-node-memory` | `node-memory > 95% for 5m clear at 90%` | warning |

### Rule syntax

```
<metric> <op> <value>[unit] [for <duration> | within <duration>] [clear at <value>[unit]] [after <duration>]
```

| Part | Rules |
|---|---|
| metric | one of the ids in the table above |
| op | `<`, `<=`, `>`, `>=` |
| value | up to 10 digits, optionally a point and up to 3 decimals, no leading zero |
| unit | `%` for percent metrics (required), `KB` `MB` `GB` `TB` for byte metrics (required, powers of 1024), `ms` for `mspt` (optional), nothing for the rest |
| `for` | how long the condition must hold before the alert fires; default 0 (first sample). Not for `crashes`. |
| `within` | counting window of `crashes`, required there, 1s to 24h. Not for other metrics. |
| `clear at` | hysteresis: the value at which a firing alert counts as healthy. It must lie on the healthy side: at or above the threshold for `<` rules, at or below for `>` rules. Default: the threshold. |
| `after` | debounce when clearing: how long the value must stay healthy. Default: the `for` duration. |
| durations | `<number>s`, `m` or `h`, at most 24h |

Values outside the metric's range (TPS 0 to 1000, percent 0 to 100, bytes up to 1024 TB) and fractions for counts are errors. The whole expression is at most 160 printable ASCII characters. Error messages give the position and the rule that was broken, never the text.

### How a rule is evaluated

Per rule and service (or node) there is one state:

```
OK --condition true--> PENDING --held for "for"--> FIRING --healthy ("clear at")--> CLEARING --held for "after"--> OK
                          |                           ^                                |
                          +--condition false--> OK    +------- not healthy any more ---+
```

- **Debounce:** `for 60s` fires on the first sample that is at least 60 s after the first bad sample in an unbroken row. One good sample starts the timer over.
- **Hysteresis:** with `tps < 18 clear at 19`, a server at 18.5 TPS keeps its alert; it ends only at 19 or more. Without `clear at`, a value that bounces around 18 would fire and clear on every sample.
- **Missing values** (no report yet, outdated report, proxy, `heapMax` unknown, `rss` without a source) never fire and never clear. A pending timer starts over; a firing alert stays.
- **Not running:** gauge rules (everything except `crashes` and `restarts`) apply only while a service is `RUNNING`. When it stops or crashes, its gauge alerts end with the resolution "service gone". `crashes` and `restarts` keep counting, so a crash loop still fires while the service is down.
- **Gone services:** a service that leaves the directory ends all its alerts.
- **Rule changes:** `Monitor.replaceRules` keeps the state of unchanged rules and ends alerts of changed or removed ones.

Each firing alert gets a number (`#1`, `#2`, ... per run of the cloud). The log gets a warning when an alert fires (`Alert #3 low-tps on lobby-1: tps 12.4 (rule tps < 18 for 1m)`) and an info line when it ends or is acknowledged. Every event also goes to the `AlertListener`s as `AlertEvent.Fired`, `Resolved` or `Acknowledged` (sealed), for the API (PR 15) and later the dashboard.

## Notifications

`Notifier` is the interface for anything that sends alerts out of the cloud; a Discord bot, mail or a pager implements it. `NotificationDispatcher` runs the notifiers on a thread of its own with a queue of 100 events, so a slow webhook never delays sampling; when the queue is full, events are dropped and counted. Acknowledgements are not sent; `min-severity` and `send-resolved` filter the rest.

The built-in `WebhookNotifier` posts JSON to a URL the operator enters:

1. Create the webhook yourself, for example in Discord under channel settings, Integrations, Webhooks.
2. Put the URL into `secrets/vectora.env`: `VECTORA_MONITOR_WEBHOOK_URL=https://discord.com/api/webhooks/...`
3. Set `notifications.webhook.enabled: true` in `monitor.yml` and restart the cloud.

Rules of the webhook:

| Rule | How |
|---|---|
| The URL is a secret | It lives in `vectora.env` (owner-only), not in `monitor.yml`. `WebhookUrl.toString()` prints `***`; log lines go through the secret filter as well. Errors name the HTTP status or the kind of network error, never the URL or the host. |
| HTTPS only | `http://`, other schemes, user info before the host, fragments, spaces and non-ASCII characters are refused when the cloud starts. The JDK trust store checks the certificate. |
| No redirects | Redirects are never followed, so a post cannot be sent on to another host. A 3xx answer is a failure. |
| Time limit | `timeout` applies to connecting and to the answer. |
| Content | Rule names, service ids and numbers only. Discord posts set `allowed_mentions` to none, so a post cannot ping `@everyone`. The chat formats escape their markup on top: `discord` puts a backslash before `\`, `*`, `_`, `~`, `` ` ``, `\|`, `<`, `>` and `@`, `slack` writes `&`, `<` and `>` as `&amp;`, `&lt;` and `&gt;`. `json` sends the raw values; a receiver of your own escapes them for its output. |
| Target address | The cloud does not check where the URL points. A receiver on your own network (a bot, a relay, a ticket system) is a valid use, so private and loopback addresses are allowed. The URL comes only from `vectora.env`, which only the operator can write; whoever can edit that file can point the cloud at any host the node reaches. |

An enabled webhook whose key is missing or whose URL breaks a rule is logged as a warning when the cloud starts; monitoring runs without it.

Formats: `discord` sends `{"content": "...", "allowed_mentions": {"parse": []}}`, `slack` sends `{"text": "..."}` (Slack, Mattermost), `json` sends every field (`event`, `id`, `rule`, `subject`, `severity`, `metric`, `value`, `condition`, `fired-at`, `resolved-at`, `resolution`, `origin`, `text`) for a receiver of your own.

## Console commands

`MonitorCommands` defines the commands; the node registers them in its console.

| Command | Shows |
|---|---|
| `monitor` | node CPU, memory and free space in the home, then one line per service: state, players, TPS, MSPT, heap, CPU, RSS, restarts, crashes; and the number of active alerts |
| `monitor <service> [metric]` | the history of one metric as an ASCII chart with now, minimum, average and maximum. Metrics: `tps` (default), `mspt`, `heap`, `heap-used`, `players` (default on proxies), `cpu`, `rss` |
| `alerts list` | active alerts (number, severity, rule, service, since, value, acknowledged) and the last ten that ended |
| `alerts ack <number\|all>` | acknowledges alerts; they stay active until they clear |

The chart uses `#`, `-`, `.` and `|` only, so it looks the same in JLine, the plain console, a panel and a log file. A time slot without values is blank and marked `.` on the axis.

```
lobby-1 tps (2026-10-29 12:00:00 - 2026-10-29 12:59:50)
20.0 |##########################      ############################
     |##########################      ############################
     |##########################  ##  ############################
     |##########################  ##  ############################
     |################################################################
     |################################################################
     |################################################################
 0.0 |################################################################
     +--------------------------------------------------------------
      -59m                                                       now
  now 19.9  min 9.8  avg 18.7  max 20.0
```

## History

Each service and the node have a ring buffer of `history.samples` samples in memory. With `history.persist: true` the monitor also writes `state/monitor-history.bin` every `persist-interval` and on shutdown, and reads it back at the next start.

- Compact binary format (about 50 bytes per service sample), described in `HistoryFile`.
- Written atomically (temporary file, flush, rename).
- Samples older than `retention` are dropped on writing and reading; each series is capped at `history.samples`.
- Read strictly: wrong magic or version, counts beyond the limits, times outside 2000 to 2100, negative values other than the unknown marker, invalid service ids and trailing bytes are errors. A damaged file is moved aside as `monitor-history.bin.broken-<millis>`; a symbolic link or folder in its place is neither read nor replaced.

## Prometheus (optional)

With `prometheus.enabled: true` the cloud serves `GET /metrics` in the Prometheus text format on `bind:port`:

- Loopback only. Any other address is a configuration error, and the endpoint refuses to bind elsewhere as well. Reach it from another machine through an SSH tunnel or a local agent.
- Requests whose `Host` header is not a loopback name get `421`, so a web page cannot read the metrics through DNS rebinding. Other paths get `404`, other methods `405`.
- No authentication; the values contain no secrets. Labels are only `service`, `group` and `severity`: no addresses, paths, pids or player names.
- Limits: one handler thread and a backlog of 16 connections; request bodies are never read and the answer is built from memory. The JDK server sets no further limit on connections or on slow requests, so this endpoint trusts every local process. Leave it off on a machine shared with users you do not trust, or put a reverse proxy with limits in front of it.
- It uses the JDK's `jdk.httpserver` module, no extra library.

`PrometheusFormat.render(monitor)` builds the text without any I/O, so the API (PR 15) can serve the same output on its own server and this endpoint can go. Series: `vectora_node_cpu_percent`, `vectora_node_memory_used_bytes`, `vectora_node_memory_total_bytes`, `vectora_node_disk_free_bytes`, `vectora_node_disk_total_bytes`, and per service with the labels `service` and `group`: `vectora_service_running`, `_restart_attempts`, `_crashes_total`, `_players`, `_max_players`, `_tps`, `_mspt`, `_heap_used_bytes`, `_heap_max_bytes`, `_cpu_percent`, `_rss_bytes`; finally `vectora_alerts_active{severity}`. Values that were not measured are left out.

## Hooking it into the node

`MonitorRuntime.open(home, directory, secrets, RedactingFilter::redact, nodeId)` reads `monitor.yml`, builds the monitor on `SystemMetricSource`, the webhook, the history file and the Prometheus endpoint, and offers the console commands. `cloud/node` wires it in `CloudNode`; the module itself needs no change to `cloud/node`:

| Step | Where in the node | Code |
|---|---|---|
| Service list | adapter on `ServiceManager.services()` and the group configuration | `ServiceDirectory directory = () -> services.services().stream().map(s -> new MonitoredService(s.id(), s.group(), config.snapshot().groups().get(s.group()).software() == ServerSoftware.VELOCITY, ServicePhase.valueOf(s.state().name()), s.pid(), s.restartAttempts())).toList();` |
| Open | `CloudNode.open`, after the configuration and secrets | `MonitorRuntime monitoring = MonitorRuntime.open(home, directory, secrets, RedactingFilter::redact, nodeId);` An invalid `monitor.yml` fails here with every error, like the other configuration files. |
| Status reports | `ChannelBridge.onEvent` | `case ServiceEvent.StatusReported r -> monitoring.monitor().statusReported(r.status());` |
| State changes | a `ServiceListener` (`ServiceManager.addListener`) | `stateChanged(info, previous) -> monitoring.monitor().serviceStateChanged(info.id(), ServicePhase.valueOf(info.state().name()), previous == null ? null : ServicePhase.valueOf(previous.name()))` |
| Console | after the console is built | `console.commands().register(monitoring.commands().monitor()); console.commands().register(monitoring.commands().alerts());` |
| Start | after the cloud channel, before the first service | `monitoring.start();` |
| Stop | `CloudNode.close()`, after the last service | `monitoring.close();` (writes the history file, sends queued notifications) |
| Autoscaler | `CloudNode.buildOps` | `ScalingSignals.of(monitoring.monitor(), channel.players(), channel::proxyWatches, clock)`: `players` of the newest sample (at most what the proxies see on the backend while one is connected) and `node-memory` decide scaling ([scaling.md](scaling.md#signals)) |
| API (PR 15) | | `monitoring.monitor().addListener(...)` for alert events; `services()`, `history(id)`, `node()`, `activeAlerts()` for reads; `PrometheusFormat.render(...)` for `/metrics` |

`ServicePhase` has the same names as `ServiceState` in `cloud/node`, so the mapping is `valueOf(state.name())`. The calls return at once and are safe from Netty and node threads.

Sampling runs on the daemon thread `vectora-monitor`, notifications on `vectora-monitor-notify`, the endpoint on `vectora-monitor-prometheus`. A change to `monitor.yml` takes effect at the next start of the cloud; `Monitor.replaceRules` is there for a reload hook later.

## Platform notes

| Platform | Process CPU | Process memory | Node |
|---|---|---|---|
| Linux | `ProcessHandle` | `/proc/<pid>/status` (`VmRSS`) | MXBean; `MemAvailable` outside containers |
| Windows | `ProcessHandle` | `%SystemRoot%\System32\tasklist.exe /FO CSV /NH`, one call per sample for all processes; the locale's number format is handled | MXBean |
| macOS, BSD | `ProcessHandle` | `/bin/ps -o pid=,rss= -p ...`, one call per 100 processes | MXBean |
| anything else | `ProcessHandle` if the JDK supports it | not measured (`-`) | MXBean if present |

Helper programs run by absolute path, without a shell and without the node's `VECTORA_*` variables, with a 30 s time limit (`tasklist` took more than 20 s on a busy Windows machine) and at most 4 MiB of output. A helper that runs out of time is killed and reported as a time-out, not by its exit code. A failing helper is logged once; `rss` stays unknown until it works again.

## Tests

| Test | Covers |
|---|---|
| `RuleParserTest` | every rule of the syntax, each error reason with its position, units and ranges at their limits, canonical form, messages that never quote the input |
| `RuleParserFuzzTest` (Jazzer) | any input fails only with `RuleSyntaxException` or parses into a condition within its ranges whose canonical form parses back equal |
| `AlertEngineTest` | debounce (59 s does not fire, 60 s does; a short dip starts over), hysteresis with its counter-check without `clear at`, clear-after reset, strict and inclusive thresholds, missing values, group filter, node rules, gone services, rule changes, acknowledgement, crash windows |
| `MonitorTest` | end to end with a fake clock, directory and metric source: TPS alert and log line, stale reports, a slow process probe (the sample is dated after it), an Error in one round (sampling goes on), proxies, unknown heap maximum, stop and crash, old reports after a stop, ring buffer size, persisted history across a restart |
| `MonitorConfigLoaderTest` | defaults, a full file, error paths without values, ranges and their limits, loopback-only bind without DNS lookups, strict reader, linked file refused |
| `WebhookNotifierTest` | real HTTPS servers on loopback with a self-signed certificate: Discord body without mentions, Slack and JSON formats, redirect not followed (the second host gets nothing), timeout, HTTP 500, untrusted certificate, refused connection; no message contains the URL |
| `WebhookUrlTest`, `NotificationDispatcherTest` | URL rules; filters, failures through the redactor, full queue drops without blocking |
| `HistoryFileTest`, `HistoryFileFuzzTest` (Jazzer) | round trip with unknown values, damaged files, retention and cap, broken file moved aside, non-regular file left alone |
| `ResidentMemoryProbeTest`, `SystemMetricSourceTest` | parsers for procfs, `ps` and `tasklist` in several locales; the probe of the running OS on the test process; CPU percentages; failure logged once |
| `MonitorCommandsTest`, `TextChartTest` | console output in English and German, charts, unknown service or metric, `alerts list` and `ack` |
| `PrometheusTest` | text format, labels, left-out unknowns, endpoint on loopback with Host check, 404, 405, `HEAD` |

They run on Java 25 and, with `-PtestJava=21`, on Java 21, on Linux and Windows in CI.

```sh
./gradlew :cloud:monitor:test --tests 'net.vectora.cloud.monitor.alert.RuleParserFuzzTest.parseFailsOnlyWithSyntaxErrorsAndRoundTrips' -Pfuzz -PfuzzMaxDuration=5m
```

## Limits today

- Not wired into the node yet; see [Hooking it into the node](#hooking-it-into-the-node).
- Changes to `monitor.yml` need a restart of the cloud.
- One node only. With the cluster (PR 20) each node runs its own monitor; a combined view comes with the API and the dashboard.
- Rules compare one metric with a fixed value. Rates of change and rules across several services are not supported.
