# VectolynCloud REST API

Module `cloud/api`. A JSON API over HTTP/1.1 plus a WebSocket for live events, for the dashboard (stage 8) and for scripts. The machine-readable description is [`api.openapi.yaml`](api.openapi.yaml), also published at <https://vectode.github.io/VectolynCloud/api/openapi.yaml>. `OpenApiContractTest` compares it with the routing table of the server in every build: a route without its entry, an entry without its route or a wrong scope fails the build. German: [api.de.md](api.de.md).

The API is off by default. It starts with the node when `api.enabled: true` is set in `config/cloud.yml`:

```yaml
api:
  enabled: true
  bind: 127.0.0.1          # loopback by default; any other address needs TLS
  port: 8085
  tls:
    enabled: false
    # keystore: secrets/api.p12
  # cors-origins: [https://dashboard.example.org]   off by default
```

Changes to `api:` take effect at the next start of the node; the running server keeps its socket.

## Tokens

Every request needs a token. Without one, with a wrong, a revoked or an expired one the answer is `401` with `WWW-Authenticate: Bearer`. There is no anonymous endpoint and no default password; the first token comes from the console or from the one-time [setup token](#first-administrator).

Tokens are managed at the node console, or by a token with `admin` over [`/api/v1/admin/tokens`](#token-management). The whole `api` command is sensitive, so none of its lines is written to the console history.

```text
vectolyn> api token create dashboard services:read,config:read,node:read
Token dashboard (3f9a01c2) created with services:read, config:read, node:read.
  vct_...
Copy it now: it is shown only this once and is not stored. Lost tokens are revoked and created again.
vectolyn> api token create ci services:read 90d    # stops working after 90 days
vectolyn> api token list
vectolyn> api token revoke dashboard          # or the id; aliases: delete, remove
vectolyn> api token setup owner               # new one-time setup token for the first administrator
vectolyn> api scopes
```

- Format: `vct_` followed by 43 characters `[A-Za-z0-9_-]` (32 random bytes from `SecureRandom`, Base64url).
- The node stores only the SHA-256 of each token, in `<home>/secrets/api-tokens.json` (owner only, written atomically, symbolic links refused). Neither `list` nor the API ever shows a token or its hash.
- A token file that cannot be read (malformed, a link, too large) does not stop the node. With `api.enabled: false` the node logs a warning; with the API on it logs an error and runs without the API, as with TLS material it cannot use. `api token create`, `list` and `revoke` then fail with the reason (`secrets/api-tokens.json cannot be read (…)`) and try the file again each time: once it is repaired, or deleted to start without tokens, they work. The API itself comes back with the next start of the node.
- Checking a token compares its hash with every stored hash in constant time (`MessageDigest.isEqual`), without stopping at the first match.
- Revoking works at once: the next request fails, and open WebSockets of that token are closed with code `1008`.
- **Lifetime.** A token can be given a lifetime of 1h to 3650d (`12h`, `90d`). From `expires-at` on it fails with `401` like a revoked one, and its open WebSockets are closed with `1008` within 15 seconds. It stays in `list` (marked expired) until it is revoked, so its name is not taken over by accident. Without a lifetime a token works until it is revoked. The expiry is stored with the hash in `api-tokens.json` (`expires-at`, optional, still `schema-version: 1`; an older node refuses a file with an expiring token instead of letting it work forever).
- At most 100 tokens. Names follow the group name rule `[a-z0-9][a-z0-9_-]{0,31}`.
- Where the printed token ends up: the command writes to the console, not to the log, so the log files and the journal do not get it. Under systemd the cloud has no console at all; create tokens in a `tmux` or `screen` session ([operations](operations.md)). In Docker, the console of an attached container (`docker attach`) is the container's standard output, so `docker logs` keeps the token. Clear or rotate those logs, or revoke the token and create a new one if the logs are shared.

Send the token as `Authorization: Bearer vct_...`. Browsers cannot set headers on a WebSocket, so the event stream also takes it as the subprotocol `vectolyn.bearer.<token>` next to `vectolyn.v1`; the server answers with `vectolyn.v1` only and never echoes the token. An upgrade that offers subprotocols but not `vectolyn.v1` is refused with `400 subprotocol-required`.

### First administrator

At the end of the first setup the console shows a one-time setup token for the administrator named there ([setup](setup.md#first-administrator)); `api token setup [name]` makes a new one (default name `admin`). It is `vcs_` and 43 characters from 32 bytes of `SecureRandom`, valid for 24 hours and exactly once; only its SHA-256, the name and the expiry are stored, in `<home>/secrets/setup-token.json` (owner-only, atomic, no links). A new setup token replaces the old one.

```sh
curl -X POST -H "Authorization: Bearer vcs_..." https://cloud.example.org:8443/api/v1/setup/claim
# 201 {"id":"a1b2c3d4","name":"owner","scopes":["admin"],"created-at":"...","token":"vct_..."}
```

- The setup token is accepted for this one request and nowhere else: on any other endpoint, or with a query string, it is a wrong token (`401`, counted by the limit for wrong tokens). An API token on this endpoint gets `403 setup-token-required`.
- The exchange writes the API token first. If that fails (`500 io-error`), the setup token stays valid and the claim can be repeated. Once the API token exists, the setup token stops working at once, so it can never be used twice; a second try gets `401`. Its file is deleted after that; if the deletion fails, the claim still succeeds and the node logs an error, because the file would make the setup token valid again after a restart (delete the file or replace the token with `api token setup`). The API token gets the scope `admin` and the administrator's name, with `-2`, `-3`, … if a token of that name exists.
- The answer is the only time the API token leaves the node (`Cache-Control: no-store`). Lost: revoke it at the console and make a new setup token.
- The web panel (stage 8) will use the same request for its first login.

### Scopes

| Scope | Allows |
|---|---|
| `services:read` | list and read services; service and player events |
| `services:write` | start, stop, restart, send a console command |
| `config:read` | read `cloud.yml`, groups, tasks, server switches, warnings, setup status |
| `config:write` | write and delete groups and tasks, set `wanted`, write `cloud.yml` except `api` and `database`, reload |
| `backups:read` | list backups |
| `backups:write` | create a backup |
| `node:read` | node status; metrics events |
| `admin` | everything above, and the token management under `/admin/tokens` |

A write scope does not include the read scope. A missing scope gives `403 missing-scope`. Changing the `api` or `database` section of `cloud.yml` needs `admin`: they decide who can reach the node and which image `docker compose` runs with the database root password.

**What the write scopes can reach.** `config:write` is not a shell on the node: JVM flags are limited by the [allowlist](config.md#jvm-flag-allowlist), templates are folders of the cloud home, and software and plugins come only from the download sources with their checksums. `services:write` can send any console command to a server, which is what an operator at the server console can do; with plugins that offer console commands for files or scripts, that reaches further. Give it only to scripts you would also let use the server console. `GET /api/v1/tokens/self` works with any valid token and shows its id, name, scopes, creation time and expiry.

The rights check is tested for every route and every scope against a running server (`ScopeMatrixTest`): a token with one scope gets `403 missing-scope` exactly where the route needs another one, and `admin` gets through everywhere. `RouteScopesTest` checks the table itself: every change needs a write scope, every read a read scope, the admin area `admin`.

## Endpoints

All paths start with `/api/v1`. Bodies are `application/json` in UTF-8, with the same names (kebab-case) and the same records as the YAML files ([config.md](config.md)).

| Method and path | Scope | Answer |
|---|---|---|
| `GET /services` | services:read | every service of this node |
| `POST /services` `{"task": "lobby"}` | services:write | `201` the new service |
| `GET /services/{id}` | services:read | one service |
| `POST /services/{id}/stop` | services:write | the stopped service |
| `POST /services/{id}/restart` | services:write | the restarted service |
| `POST /services/{id}/command` `{"command": "say hi"}` | services:write | `202` |
| `GET /groups` | config:read | every group |
| `GET /groups/{name}` | config:read | the group, with `ETag` |
| `PUT /groups/{name}` | config:write | `201` created or `200` replaced, with `ETag` |
| `DELETE /groups/{name}?confirm={name}` | config:write | `204` |
| `PUT /groups/{name}/wanted` `{"wanted": false}` | config:write | the group's switch and whether it runs |
| `GET /tasks`, `GET/PUT /tasks/{name}`, `DELETE /tasks/{name}?confirm={name}`, `PUT /tasks/{name}/wanted` | as for groups | as for groups |
| `GET /nodes` | config:read | every node of `nodes.yml` |
| `GET /nodes/{name}` | config:read | the node, with `ETag` |
| `PUT /nodes/{name}` | config:write | `201` created or `200` replaced, with `ETag` |
| `GET /servers` | config:read | `{"proxy": true, "lobby": true, ...}` |
| `PUT /servers/{kind}` `{"wanted": true}` | config:write | the server switches |
| `GET /config` | config:read | `cloud.yml`, with `ETag` |
| `PUT /config` | config:write | the new `cloud.yml`, with `ETag` |
| `POST /config/reload` | config:write | `{"changed": true, "files": ["groups/lobby.yml"]}` |
| `GET /config/warnings` | config:read | flags kept from before the allowlist, see below |
| `GET /node` | node:read | node id, version, Java, OS, uptime, heap, services total and running, shutdown flag |
| `GET /setup` | config:read | `eula-accepted`, `setup-complete`, `ready`, `missing` (`eula`, `setup`) |
| `GET /backups` | backups:read | every backup |
| `POST /backups` `{"services": ["lobby-1"], "database": true, "note": "before update"}` | backups:write | `201` the backup |
| `GET /tokens/self` | any | the calling token |
| `GET /admin/tokens` | admin | every token (never the token or its hash), paged |
| `POST /admin/tokens` `{"name": "ci", "scopes": ["services:read"], "expires-in": "90d"}` | admin | `201` the token, shown in this answer only |
| `DELETE /admin/tokens/{id}` | admin | `204`; id or name |
| `POST /setup/claim` | the setup token only | `201` a new `admin` token with its value, see [first administrator](#first-administrator) |
| `GET /events` (WebSocket) | per event type, see below | live events |

Every write goes through `ConfigService.apply`: the whole configuration is validated (ports, EULA before `setup-complete`, TLS for a public API, the JVM flag allowlist), written atomically, and the listeners of the node see the change as if it was typed at the console. On an error nothing is written and the answer is `422 invalid-config` with the list of errors.

**Wanted.** There are two switches, and a group runs only if both are on: the group's own `wanted` in `groups/<name>.yml`, and the switch of its server kind in `cloud.yml` (`servers:`, the kind whose template folder the group uses). A PandaSpigot group on a template of its own follows `servers.legacy-server`. The 1.8.8 part of the network (PandaSpigot, the `Legacy-Proxy` and `Legacy-Server` templates) also needs `legacy-proxy.enabled`, and the database template never runs as a service. This is the rule the node plans with. `PUT /groups/{name}/wanted` sets the group's switch; `PUT /tasks/{name}/wanted` sets the switch of the task's group (tasks have none of their own, so this affects every task of that group). Both answer `{"group": "lobby", "wanted": false, "server-kind": "lobby", "server-kind-wanted": true, "runs": false}`; `runs` applies the whole rule. `PUT /servers/{kind}` sets the kind's switch.

**Delete.** `DELETE` needs the name repeated as `?confirm=<name>`, otherwise `400 confirmation-required`. That stops a script from deleting the wrong thing with one typo.

**Concurrent edits.** `GET` of a group, task or `cloud.yml` returns an `ETag`. Send it back as `If-Match` with `PUT` or `DELETE`; if the document changed in between, the answer is `412 precondition-failed` and nothing is written. The check runs together with the write, so of several writers with the same `ETag` exactly one gets through. `If-Match: *` only requires the document to exist. Without `If-Match` the write is unconditional.

**Tasks.** `PUT /tasks/{name}` takes the whole task in the current format (`schema-version: 2`), for example `{"schema-version": 2, "group": "lobby", "scaling": {"static-services": 1, "min-services": 1, "max-services": 4, "players-per-service": 50, "scale-up-at": 75, "scale-down-below": 50, "scale-down-after": "5m", "cooldown": "60s"}, "nodes": []}`. `static-services` is required, `scale-down-below` defaults to 50. The same validator as for the file answers `422` with `static-above-min` (static above min) or `scale-down-not-below-up` (`scale-down-below` not lower than `scale-up-at`). A body of `schema-version: 1` (with `mode`) is migrated exactly like a file (see [config](config.md#from-schema-version-1)) and stored as version 2; the answer shows the migrated task. A body the reader refuses (unknown field, wrong type, missing required field, invalid `mode`) answers `400` with `errors` naming the field and a code such as `required` or `invalid-value`. `POST /services` with a task starts the task's lowest static service that is stopped, else a new static one while the task has fewer than `static-services`, else a dynamic one. See [scaling](scaling.md#static-and-dynamic-in-one-task).

**Nodes.** `PUT /nodes/{name}` takes the whole node as in `nodes.yml`, for example `{"host": "127.0.0.1", "port": 7010, "service-ports": {"first": 30000, "last": 30999}, "max-memory": "8G", "memory-reserve": {"system": "2G"}}`. `id` may be left out; if it is set, it must match the path, otherwise `400 invalid-value`. `max-memory` (at least 512M) and every field of `memory-reserve` (1M to 64G; defaults `system` 1G, `node-overhead` 256M, `database` 512M) are optional; a field left out is removed from the file and its default applies. The same validator as for the file answers `422` (for example `out-of-range` at `nodes[0].memory-reserve.system`). `ETag` and `If-Match` work as for groups. There is no `DELETE`; removing a node stays in the file.

**Fallback group.** `fallback-group` in `PUT /config` names the group the proxies send a kicked player to when the player's own group has no free server, for example `"fallback-group": "hub"`. It is optional (default `lobby`, then not returned); a group that does not exist answers `422 invalid-config` with `unknown-group`. Leaving the field out sets it back to the default. The proxies get it when they start (see [bridges.md](bridges.md#catching-kicks)).

**Powered-by line.** `branding.powered-by` in `PUT /config` (for example `"branding": {"powered-by": false}`) switches the "Powered by VectolynCloud" TAB footer line and `/version` on the proxies on or off. Leaving the section or the key out (`"branding": {}`) counts as `true`. Services pick the value up at their next start (see [config.md](config.md#cloudyml)).

**Groups.** `drain-timeout` is optional (5s to 10m, default 30s), for example `"drain-timeout": "45s"`; outside the range the answer is `422 out-of-range`.

<a id="token-management"></a>**Token management.** `POST /admin/tokens` creates a token like `api token create`: `name` and `scopes` are required, `expires-in` (1h to 3650d) is optional. The answer is the one place the API ever shows a token (`"token": "vct_..."`, with `Cache-Control: no-store` like every answer); the node stores only its SHA-256. A taken name or the 101st token answers `409 conflict`. `DELETE /admin/tokens/{id}` revokes by id or name at once, also the calling token itself. An `admin` token can create another `admin` token, so give `admin` only to whoever may also use the node console.

**Shutdown.** While the node shuts down, start, restart, reload and backup creation answer `503 shutting-down`. Reads and stops still work.

**Backups.** `BackupAccess` (list, create) is implemented by `BackupServiceAccess` over the `BackupService` of cloud-backup. Backups made through the API have the reason `manual`, like `backup create` at the console. A busy backup gives `409`, an unknown service `422`. Answers carry the backup id, never a path. Other failures (files, database dump, a broken archive) answer `503 unavailable` with the reason only, for example `(io)`; the details go to the node log. Until the node passes the adapter in, both endpoints answer `501 not-available`.

### Paging

The list endpoints (`GET /services`, `/groups`, `/tasks`, `/nodes`, `/backups`) are ordered by their key: service id, group, task or node name, backup id (which sorts by time). Without `limit` the answer holds the whole list. With `?limit=n` (1 to 500) it holds at most `n` entries, and `?after=<key>` starts after that key. The body keeps its shape (an array, or an object by name for groups and tasks), so a client that does not page sees no difference. Two headers carry the paging state:

- `X-Total-Count`: entries in the whole list.
- `Link: </api/v1/services?limit=50&after=lobby-50>; rel="next"` (RFC 8288) while more follow; the last page has none.

The cursor is a key, not a position: an entry that exists through both requests is neither skipped nor repeated when others are added or removed in between. A bad `limit` or `after` answers `400 invalid-query`.

### Errors

Every error has the same body:

```json
{
  "code": "invalid-config",
  "message": "The configuration was not changed: it would be invalid.",
  "errors": [
    {"file": "groups/lobby.yml", "path": "process.jvm-flags[1]", "line": 0,
     "code": "jvm-flag-forbidden", "message": "...", "message-de": "..."}
  ]
}
```

File names in `file` and in the `files` of a reload are relative to `config/` (`groups/lobby.yml`), the same as in the console. Messages name fields and rules, never values. Messages of the node (for example why a service did not start) pass the same redaction as the console output and are cut to 300 characters.

| Status | Codes |
|---|---|
| 400 | `invalid-json`, `unknown-field`, `body-required`, `invalid-value`, `invalid-target`, `invalid-path`, `invalid-query`, `confirmation-required`, `subprotocol-required` |
| 401 | `unauthorized` |
| 403 | `missing-scope`, `origin-not-allowed` |
| 404 | `not-found` |
| 405 | `method-not-allowed` (the message lists the allowed methods) |
| 409 | `conflict` |
| 412 | `precondition-failed` |
| 413 | `body-too-large` |
| 415 | `unsupported-media-type` |
| 422 | `invalid-config`, `invalid` |
| 426 | `upgrade-required` (plain `GET /events`) |
| 429 | `rate-limited` (with `Retry-After`) |
| 500 | `io-error`, `internal-error` |
| 501 | `not-available` |
| 503 | `shutting-down`, `busy`, `unavailable` |

### Strict input

- JSON is read with the same rules as the YAML files: unknown fields, duplicate fields and wrong types are refused (`400`), and the parser limits of the YAML files apply.
- A body is at most 256 KiB (`413`), also with chunked encoding: the server counts while it reads. A response that closes the connection shuts down the server's side first and drops what the client still sends for up to 2 seconds, so the client gets the `413` instead of a connection reset.
- Bodies need `Content-Type: application/json` (`415`).
- Paths and query strings are checked before routing: printable ASCII, no `%` in the path, no `.` or `..` segments, ids `[a-z0-9][a-z0-9_-]{0,63}`, names `[a-z0-9][a-z0-9_-]{0,31}`, lower-case query keys, each at most once.
- Console commands sent to a service are 1 to 1024 characters without control characters.
- HTTP pipelining is not supported; one request at a time per connection.

## Live events: `GET /api/v1/events`

A WebSocket (text frames). Optional filters as query: `?types=service-state,metrics` and `?services=lobby-1,survival-1`; an unknown type is `400`.

| Type | Scope | What |
|---|---|---|
| `service-state` | `services:read` | a service changed its state |
| `service-output` | `services:read` | a line a service printed (its log) |
| `player` | `services:read` | a player joined, switched or left |
| `metrics` | `node:read` | the node status (as `GET /node`) every 10 s |
| `node-log` | `admin` | a line of the node's own log, INFO and above |

The scope is checked per type on the handshake. Asking with `types` for a type the token may not see answers `403 missing-scope`; without `types` the stream gets every type its token may see, and a token that may see none gets `403`. A client should ignore types it does not know: new ones may be added within `vectolyn.v1`. The `services` filter applies to service and player events; `metrics` and `node-log` belong to the node and always pass it.

```json
{"type": "service-state", "time": "2026-10-29T10:00:00Z",
 "data": {"service": {"id": "lobby-1", "state": "running", ...}, "previous": "starting"}}
{"type": "service-output", "time": "...", "data": {"service": "lobby-1", "line": "Done (3.2s)!"}}
{"type": "player", "time": "...", "data": {"kind": "join", "player-id": "...", "player-name": "Steve", "service": "lobby-1"}}
{"type": "metrics", "time": "...", "data": {"node": {"node-id": "node-1", "services-running": 3, "heap-used-bytes": 123456789, ...}}}
{"type": "node-log", "time": "...", "data": {"level": "warning", "logger": "net.vectolyn.cloud.node.CloudNode", "message": "..."}}
{"type": "dropped", "time": "...", "data": {"count": 12}}
```

- Output lines and node log lines are redacted before they leave the node: every secret of `vectolyn.env` becomes `***`, as in the console. A node log line never carries a stack trace, only the name of the exception; control characters become spaces and it is cut to 2000 characters. Records of Netty and of the API itself are not streamed, so the stream cannot feed itself.
- Metrics are only gathered while a stream wants them.
- A slow client does not slow the node down: while its socket is not writable, events for it are dropped and counted, then reported in one `dropped` event.
- The server pings every 30 s and closes a stream that has been silent for 90 s. Revoking the token closes the stream with `1008`, shutting down with `1001`.
- A browser handshake with an `Origin` that is not in `api.cors-origins` is refused with `403`.
- At most 32 streams at once.
- **Load test.** `EventStreamLoadTest` opens 260 streams in waves of 40 against a limit of 200, then publishes 10,000 events from 4 threads at once to all of them. It checks that exactly 200 open, the other 60 get `503`, the REST API keeps answering, every stream accounts for every event (received or counted in `dropped`), and that every stream closes. Its figures are printed into the JUnit XML of each CI run and written to `cloud/api/build/reports/api-load/event-streams.md`; on a Windows desktop: 200 streams in 0.8 s, about 95,000 deliveries a second, 72 % delivered and the rest reported as dropped while the test clients were busy.

## GraphQL: `POST /api/v1/graphql`

A second way into the same endpoints, for clients that want several areas in one request or a typed schema. The schema is in [`schema.graphqls`](../../cloud/api/src/main/resources/net/vectolyn/cloud/api/schema.graphqls): queries for every area (services, groups, tasks, nodes, servers, `cloud.yml`, node status, setup, backups, tokens), a mutation for every change, and the subscription `events`.

```sh
curl -s -H "Authorization: Bearer $VECTOLYN_TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"{ services { totalCount items { id state } } nodeStatus { servicesRunning } }"}' \
  http://127.0.0.1:8085/api/v1/graphql
```

- **Same rules as REST.** Every field builds the REST request named in its description and runs that route with the caller's token: its scope, its validation, `If-Match` (`ifMatch` argument) and its errors. A refused or failed field is `null` and has an error with `extensions.code` and `extensions.status` (for example `missing-scope`, `403`); the other fields of the document are still answered, so the root fields are nullable. Arguments that become part of a path (ids, names, `after`) must be lower-case names, otherwise `invalid-value`.
- **Shapes.** Typed objects use camelCase (`startedAt`, `heapUsedBytes`); configuration documents keep their REST shape as the scalar `JSON` (kebab-case keys) together with their `etag`. Lists take `limit` and `after` and answer `totalCount`, `next` and `items`.
- **Limits**, all checked before a field runs: 16,384 characters, 2,000 parser tokens, selection depth 8, complexity 200 (one per field), and at most 20 fields that read or change data. The request itself takes one request from the token's rate limit, every such field after the first takes one more (`extensions.retryAfter` when it is used up).
- **Introspection** (`__schema`, `__type`) is off unless `api.graphql-introspection: true` is set in `cloud.yml` (console: `config set api.graphql-introspection true`). Switch it on for development, leave it off in production; the schema file is public anyway.
- **Subscriptions** run over the WebSocket `GET /api/v1/graphql` with the subprotocol `graphql-transport-ws` (the protocol of the `graphql-ws` client library). The token is checked on the handshake as for `/events` (header, or subprotocol `vectolyn.bearer.<token>` from a browser). Send `connection_init` within 10 s, then `subscribe` with for example `subscription { events(types: ["service-state"]) { type time data } }`. Every event type needs its scope, as on `/events`. Queries and mutations work on the socket too. At most 16 operations per socket, each `subscribe` takes one request from the rate limit. The socket counts against the 32 WebSockets and closes with `1008` when its token is revoked or expires. Protocol errors close with the codes of the protocol (`4400`, `4401`, `4408`, `4409`, `4429`).

## Transport security

### Bind and TLS

The default bind is `127.0.0.1`. Any address that is not loopback, including `0.0.0.0`, needs `api.tls.enabled: true`; the validator refuses the configuration otherwise (`api-tls-required`) and the server refuses to start as a second check.

With TLS the server speaks TLS 1.3 and 1.2 only, and sends `Strict-Transport-Security`. The keystore (`api.tls.keystore`, PKCS#12, relative to the home):

- **Own certificate.** Put the PKCS#12 file at that path and its password into `secrets/vectolyn.env` as `VECTOLYN_API_KEYSTORE_PASSWORD`. Without the password the node does not start the API and says which key is missing.
- **Generated.** If the file does not exist, the node creates a self-signed certificate with the JDK's `keytool`: EC P-256, valid for 825 days, subject names `localhost`, `127.0.0.1`, `::1` and the bind address (unless it is a wildcard). The keystore password is random and is kept in `secrets/api-keystore.password` (owner only); it is passed to `keytool` through an environment variable, never on the command line. `keytool` writes into a new owner-only folder next to the keystore, and the finished file is moved into place in one step: no other user can open the file while it is written, and a crash leaves either no keystore (generated again at the next start) or a complete one. The node logs the SHA-256 fingerprint at every start, so a client can pin it.

### CORS

Off by default: no `Access-Control-*` header is sent and browsers on other origins cannot read answers. `api.cors-origins` lists up to 16 origins (`https://host[:port]` or `http://host[:port]`, no path, no wildcard). For a listed origin the server answers preflights and adds `Access-Control-Allow-Origin` with that exact origin (`Vary: Origin`). Tokens are never sent as cookies, so there are no credentials to allow. At the console: `config set api.cors-origins https://dashboard.example.org,http://localhost:5173`, and `config set api.cors-origins ""` switches it off again.

### Limits

| Limit | Default |
|---|---|
| Requests per IP (IPv6 per /64) | 600 per minute, burst 120 |
| Requests per token | 300 per minute, burst 60 |
| Failed authentications per IP | 10 per minute, then `429` before the token is even checked |
| Body | 256 KiB |
| Requests handled at once | 64 (`503 busy` beyond) |
| Connections | 256, at most 32 per IP |
| Event streams | 32 |
| Idle connection | closed after 120 s |
| Request line / headers | 4 KiB / 8 KiB |

Limits answer `429 rate-limited` with `Retry-After` in seconds. Every answer to a request that passed the token check carries `X-RateLimit-Limit` (the size of the token's bucket) and `X-RateLimit-Remaining` (what the bucket holds now), so a script can slow down before it gets `429`.

Each limiter tracks up to 10,000 keys (addresses, tokens) in order of last use. A new key beyond that evicts the one used longest ago, in constant time: a flood of new addresses neither locks out new clients nor makes a request walk the whole table. An evicted key starts again with a full bucket; a client gains nothing by that unless it controls more than 10,000 addresses, and then each of them has its own bucket anyway.

Addresses are counted per IPv4 address and per IPv6 /64. A client behind NAT64 (`64:ff9b::/96`, RFC 6052) is counted under the IPv4 address in the last 32 bits, so the clients behind one NAT64 gateway do not share one /64 and one client counts the same over IPv4 and NAT64.

### Response headers

Every answer has `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` and `Referrer-Policy: no-referrer`.

### What is never in an answer

Passwords, the database password, tokens and token hashes, the keystore password and the contents of `vectolyn.env`. `cloud.yml`, groups and tasks hold no secrets by design ([config.md](config.md#secrets)).

### JVM flags

Groups can be written through the API, so `process.jvm-flags` has an allowlist: a stolen `config:write` token must not become code execution on the node. See [config.md](config.md#jvm-flag-allowlist).

## Wiring in cloud-node

`cloud/api` does not depend on `cloud/node`. The node provides an `ApiBackend` (`ops.NodeApi`) and starts the server. `CloudNode` follows these steps; player events come from the channel's `NetworkPlayers`. A refusal of the service core reaches the client only if it is one of its own short reasons and holds nothing like a path; any other message stays in the node log and the client gets a short text. `ApiSettings.of` refuses `api.tls.enabled` without key material and key material with TLS off. `runs` in the wanted endpoints is `GroupRules.isWanted` of cloud-config, the rule the node itself plans by.

1. At start, after `ConfigService.open`: `ApiTokens tokens = ApiTokens.open(home)` and register `new ApiCommands(tokens).command()` in the console registry, so tokens can be created even while the API is off. If `open` fails, the node logs it (a warning with the API off, an error with it on), starts no API server and registers `new ApiCommands(() -> ApiTokens.open(home))`, which tries again on each command and reports the reason while it fails.
2. If `api.enabled`:
   ```java
   ApiConfig api = config.snapshot().cloud().api();
   // keystorePassword: VECTOLYN_API_KEYSTORE_PASSWORD from secrets/vectolyn.env, if set
   Optional<ApiTls.Material> tls = api.tls() != null && api.tls().enabled()
           ? Optional.of(ApiTls.prepare(home, api.tls(), api.bind(), keystorePassword))
           : Optional.empty();
   ApiServer server = ApiServer.builder(config, backend, tokens)
           .settings(ApiSettings.of(api, tls))
           .build();
   server.start();          // throws ApiStartException (an IOException) if it cannot bind
   ```
   A failed bind (port in use, address not on this machine) throws an `IOException` that names the port, after both event loop groups have been shut down together and their threads have ended. The node logs it as an error and runs without the API.
3. `ApiBackend` on top of the node:
   - `services()`: a `ServiceAccess` over `ServiceManager` (list, find, start by task, stop, restart, command), mapping a service to `ServiceView`. Failures are thrown as `ApiBackendException` with `NOT_FOUND`, `CONFLICT`, `UNAVAILABLE` or `INVALID`.
   - `node()`: `NodeStatus.ofThisJvm(nodeId, version, startedAt, shuttingDown, servicesTotal, servicesRunning)` and `shuttingDown()` of the node.
   - `backups()`: `Optional.of(new BackupServiceAccess(backupService))`.
   - `redact(String)`: the node's `RedactingFilter`.
4. Events: in the `ServiceListener`, `server.publish(new ApiEvent.ServiceState(view, previous))` on every state change and `server.publish(new ApiEvent.ServiceOutput(id, line))` for every output line; player join and quit from the bridges as `ApiEvent.Player`. `publish` never blocks.
5. Shutdown: `server.beginShutdown()` first in `CloudNode.close()` (refuses starts and reloads), `server.close()` last (closes streams with `1001`, then the sockets).

## Tests

`ApiServerTest`, `PagingTest`, `EventStreamTest`, `ApiTlsTest`, `ApiTokensTest`, `RateLimiterTest`, `ClientAddressTest`, `RouterTest` and `ApiCommandsTest` run against a real server on `127.0.0.1` with a random port. `ApiServerTest.aPortInUseLeavesNoEventLoopThreadBehind` binds to a taken port and checks that no `vectolyn-api` thread is left; `ApiCommandsTest.aBrokenTokenFileIsReportedUntilItIsRepaired` covers the commands on a broken file. `NodeRobustnessTest` in `cloud/node` checks the same on the whole node: a broken token file with the API off and on, and a taken API port. Fuzz targets (Jazzer, see [architecture](architecture.md#fuzz-tests)):

- `RouterFuzzTest.resolveNeverThrowsAndOnlyPassesCleanValues`: against the real table of `ApiRoutes.router()`, any request target resolves without an exception, and a found route only carries parameters and query values from the strict alphabets.
- `ApiJsonFuzzTest.readFailsOnlyWithBadRequest`: any body becomes a value or fails with a `400` whose message has no control characters.
- `GraphQlFuzzTest.anyDocumentEndsInAResult`: any GraphQL document runs to a result (data, errors or both) without an exception, against the real routes with a read-only token.
- `GraphQlFuzzTest.anyBodyIsARequestOrABadRequest`: any body of `POST /graphql` is a GraphQL request or a `400`.

`GraphQlTest` covers the endpoint against a real server: several areas in one document, the scope of every field, arguments that try to leave their path segment, the `ETag` round trip, the limits, introspection on and off, the rate limit per field, and subscriptions over `graphql-transport-ws` with their scopes, protocol errors and revocation.

```sh
./gradlew :cloud:api:test --tests 'net.vectolyn.cloud.api.http.RouterFuzzTest.resolveNeverThrowsAndOnlyPassesCleanValues' -Pfuzz -PfuzzMaxDuration=5m
```
