# Configuration

[Deutsch](Configuration-DE)

How VectolynCloud is configured. The setup assistant writes the first configuration; after that you change it through files, console commands or the [API](API). All three go through the same validation, so a value that the console refuses is refused in a file and in the API as well. The full reference with every key is [docs/cloud/config.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/config.md).

## Files

Everything lives in the cloud home, the folder VectolynCloud runs in.

| File | Content |
|---|---|
| `config/cloud.yml` | network name, language, EULA and setup state, which of the servers are wanted, ports of the proxy, legacy proxy, database and API, branding |
| `config/nodes.yml` | the nodes: host, channel port, service port range, memory limit and reserve |
| `config/groups/<name>.yml` | what a service runs: template, software (`paper`, `velocity`, `fabric` or `pandaspigot`), Minecraft version, Java, heap, plugins and mods from the catalog, JVM flags, restart behaviour |
| `config/tasks/<name>.yml` | how many services of a group run: static, minimum, maximum, players per service, scaling thresholds |
| `config/monitor.yml`, `config/backup.yml` | monitoring and backups, see [monitoring](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/monitor.md) and [backups](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/backup.md) |
| `config/cluster.yml` | head, members and revoked certificates of a cluster |
| `config/software.lock.yml` | every downloaded artifact with its hash; written by the cloud, not by hand |
| `secrets/vectolyn.env` | generated secrets (owner only, never in a repository) |

Every file starts with `schema-version`. Field names are kebab-case and enum values are lower case. Unknown fields, duplicate fields and wrong types are errors with the file, the path and the line.

## Changing it

- **By hand.** Edit a file and run `config reload` in the console. An invalid file keeps the current configuration and prints the errors.
- **Console.** `config get|set|list|reload`, `group list|info|create|set|delete|wanted`, `task list|info|create|set|delete|wanted`, `node list|info|set` and `setup` (runs the assistant again). `help <command>` shows the usage.
- **API.** `GET` and `PUT` on `/api/v1/config`, `/groups`, `/tasks`, `/nodes`, `/servers`, with `ETag` and `If-Match` against concurrent edits.

The cloud rewrites a file when it changes it and drops comments in that file; keep notes elsewhere.

## Choosing servers

`servers:` in `cloud.yml` holds one switch per server: proxy, lobby, survival, creative, mod, end, database, minigame and legacy-server. A group runs only if its own `wanted` and the switch of its server kind are both on. Servers that are not wanted are never created, and neither proxy nor lobby refers to them.

## Scaling

A task decides how many services of a group run. The first `static-services` are static and keep their folder; above them the cloud adds dynamic services when the load of the group passes `scale-up-at` and removes them below `scale-down-below`, within `min-services` and `max-services`. Optional `scale-up-on` adds services on high CPU, low TPS or a full heap, and `schedules` raise the minimum at set times. The node respects its memory budget. Details: [docs/cloud/scaling.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/scaling.md).

## Database

`database.mode` is `managed` (the cloud downloads and runs MariaDB), `docker`, `external` (an existing MariaDB or MySQL) or `h2` (embedded). MariaDB has no server build for anything except Windows x64 and Linux x86_64, so `managed` falls back to H2 on other systems. Connection pools are sized centrally. Details: [docs/cloud/database.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/database.md).

## Setup without a terminal

With `--non-interactive --setup-file setup.yml` the assistant reads its answers from a file. The file holds no secret; a database password comes from a file or from `secrets/vectolyn.env`. It counts only while `setup-complete` is false. Format: [docs/cloud/setup.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/setup.md#setup-file---setup-file).

## Not available yet

A JSON Schema for editors, import and export of the whole configuration, a preview of a change before it is applied, PostgreSQL and the panel editor are planned ([#129](https://github.com/VectoDE/VectolynCloud/issues/129), [#151](https://github.com/VectoDE/VectolynCloud/issues/151)). They are not part of any release yet.
