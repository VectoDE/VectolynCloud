# VectoraCloud REST API

Module `VectoraCloud/cloud-api`. A JSON API over HTTP/1.1 plus a WebSocket for live events, for the dashboard (stage 8) and for scripts. The machine-readable description is [`api.openapi.yaml`](api.openapi.yaml). German: [api.de.md](api.de.md).

The API is off by default. It starts with the node when `api.enabled: true` is set in `cloud.yml`:

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

Every request needs a token. Without one, with a wrong one or with a revoked one the answer is `401` with `WWW-Authenticate: Bearer`. There is no anonymous endpoint.

Tokens are managed at the node console. The whole `api` command is sensitive, so none of its lines is written to the console history.

```text
vectora> api token create dashboard services:read,config:read,node:read
Token dashboard (3f9a01c2) created with services:read, config:read, node:read.
  vct_...
Copy it now: it is shown only this once and is not stored. Lost tokens are revoked and created again.
vectora> api token list
vectora> api token revoke dashboard          # or the id; aliases: delete, remove
vectora> api scopes
```

- Format: `vct_` followed by 43 characters `[A-Za-z0-9_-]` (32 random bytes from `SecureRandom`, Base64url).
- The node stores only the SHA-256 of each token, in `<home>/secrets/api-tokens.json` (owner only, written atomically, symbolic links refused). Neither `list` nor the API ever shows a token or its hash.
- Checking a token compares its hash with every stored hash in constant time (`MessageDigest.isEqual`), without stopping at the first match.
- Revoking works at once: the next request fails, and open WebSockets of that token are closed with code `1008`.
- At most 100 tokens. Names follow the group name rule `[a-z0-9][a-z0-9_-]{0,31}`.
- Where the printed token ends up: the command writes to the console, not to the log, so the log files and the journal do not get it. Under systemd the cloud has no console at all; create tokens in a `tmux` or `screen` session ([operations](operations.md)). In Docker, the console of an attached container (`docker attach`) is the container's standard output, so `docker logs` keeps the token. Clear or rotate those logs, or revoke the token and create a new one if the logs are shared.

Send the token as `Authorization: Bearer vct_...`. Browsers cannot set headers on a WebSocket, so the event stream also takes it as the subprotocol `vectora.bearer.<token>` next to `vectora.v1`; the server answers with `vectora.v1` only and never echoes the token. An upgrade that offers subprotocols but not `vectora.v1` is refused with `400 subprotocol-required`.

### Scopes

| Scope | Allows |
|---|---|
| `services:read` | list and read services, event stream |
| `services:write` | start, stop, restart, send a console command |
| `config:read` | read `cloud.yml`, groups, tasks, server switches, warnings, setup status |
| `config:write` | write and delete groups and tasks, set `wanted`, write `cloud.yml` except `api` and `database`, reload |
| `backups:read` | list backups |
| `backups:write` | create a backup |
| `node:read` | node status |
| `admin` | everything above |

A write scope does not include the read scope. A missing scope gives `403 missing-scope`. Changing the `api` or `database` section of `cloud.yml` needs `admin`: they decide who can reach the node and which image `docker compose` runs with the database root password.

**What the write scopes can reach.** `config:write` is not a shell on the node: JVM flags are limited by the [allowlist](config.md#jvm-flag-allowlist), templates are folders of the cloud home, and software and plugins come only from the download sources with their checksums. `services:write` can send any console command to a server, which is what an operator at the server console can do; with plugins that offer console commands for files or scripts, that reaches further. Give it only to scripts you would also let use the server console. `GET /api/v1/tokens/self` works with any valid token and shows its id, name, scopes and creation time.

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
| `GET /events` (WebSocket) | services:read | live events |

Every write goes through `ConfigService.apply`: the whole configuration is validated (ports, EULA before `setup-complete`, TLS for a public API, the JVM flag allowlist), written atomically, and the listeners of the node see the change as if it was typed at the console. On an error nothing is written and the answer is `422 invalid-config` with the list of errors.

**Wanted.** There are two switches, and a group runs only if both are on: the group's own `wanted` in `groups/<name>.yml`, and the switch of its server kind in `cloud.yml` (`servers:`, the kind whose template folder the group uses). A PandaSpigot group on a template of its own follows `servers.legacy-server`. The 1.8.8 part of the network (PandaSpigot, the `Legacy-Proxy` and `Legacy-Server` templates) also needs `legacy-proxy.enabled`, and the database template never runs as a service. This is the rule the node plans with. `PUT /groups/{name}/wanted` sets the group's switch; `PUT /tasks/{name}/wanted` sets the switch of the task's group (tasks have none of their own, so this affects every task of that group). Both answer `{"group": "lobby", "wanted": false, "server-kind": "lobby", "server-kind-wanted": true, "runs": false}`; `runs` applies the whole rule. `PUT /servers/{kind}` sets the kind's switch.

**Delete.** `DELETE` needs the name repeated as `?confirm=<name>`, otherwise `400 confirmation-required`. That stops a script from deleting the wrong thing with one typo.

**Concurrent edits.** `GET` of a group, task or `cloud.yml` returns an `ETag`. Send it back as `If-Match` with `PUT` or `DELETE`; if the document changed in between, the answer is `412 precondition-failed` and nothing is written. The check runs together with the write, so of several writers with the same `ETag` exactly one gets through. `If-Match: *` only requires the document to exist. Without `If-Match` the write is unconditional.

**Shutdown.** While the node shuts down, start, restart, reload and backup creation answer `503 shutting-down`. Reads and stops still work.

**Backups.** `BackupAccess` (list, create) is implemented by `BackupServiceAccess` over the `BackupService` of cloud-backup. Backups made through the API have the reason `manual`, like `backup create` at the console. A busy backup gives `409`, an unknown service `422`. Answers carry the backup id, never a path. Other failures (files, database dump, a broken archive) answer `503 unavailable` with the reason only, for example `(io)`; the details go to the node log. Until the node passes the adapter in, both endpoints answer `501 not-available`.

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

Messages name fields and rules, never values. Messages of the node (for example why a service did not start) pass the same redaction as the console output and are cut to 300 characters.

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

A WebSocket (text frames). Optional filters as query: `?types=service-state,service-output,player` and `?services=lobby-1,survival-1`; an unknown type is `400`.

```json
{"type": "service-state", "time": "2026-10-29T10:00:00Z",
 "data": {"service": {"id": "lobby-1", "state": "running", ...}, "previous": "starting"}}
{"type": "service-output", "time": "...", "data": {"service": "lobby-1", "line": "Done (3.2s)!"}}
{"type": "player", "time": "...", "data": {"kind": "join", "player-id": "...", "player-name": "Steve", "service": "lobby-1"}}
{"type": "dropped", "time": "...", "data": {"count": 12}}
```

- Output lines are redacted before they leave the node: every secret of `vectora.env` becomes `***`, as in the console.
- A slow client does not slow the node down: while its socket is not writable, events for it are dropped and counted, then reported in one `dropped` event.
- The server pings every 30 s and closes a stream that has been silent for 90 s. Revoking the token closes the stream with `1008`, shutting down with `1001`.
- A browser handshake with an `Origin` that is not in `api.cors-origins` is refused with `403`.
- At most 32 streams at once.

## Transport security

### Bind and TLS

The default bind is `127.0.0.1`. Any address that is not loopback, including `0.0.0.0`, needs `api.tls.enabled: true`; the validator refuses the configuration otherwise (`api-tls-required`) and the server refuses to start as a second check.

With TLS the server speaks TLS 1.3 and 1.2 only, and sends `Strict-Transport-Security`. The keystore (`api.tls.keystore`, PKCS#12, relative to the home):

- **Own certificate.** Put the PKCS#12 file at that path and its password into `secrets/vectora.env` as `VECTORA_API_KEYSTORE_PASSWORD`. Without the password the node does not start the API and says which key is missing.
- **Generated.** If the file does not exist, the node creates a self-signed certificate with the JDK's `keytool`: EC P-256, valid for 825 days, subject names `localhost`, `127.0.0.1`, `::1` and the bind address (unless it is a wildcard). The keystore password is random and is kept in `secrets/api-keystore.password` (owner only); it is passed to `keytool` through an environment variable, never on the command line. The node logs the SHA-256 fingerprint at every start, so a client can pin it.

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

Limits answer `429 rate-limited` with `Retry-After` in seconds.

### Response headers

Every answer has `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` and `Referrer-Policy: no-referrer`.

### What is never in an answer

Passwords, the database password, tokens and token hashes, the keystore password and the contents of `vectora.env`. `cloud.yml`, groups and tasks hold no secrets by design ([config.md](config.md#secrets)).

### JVM flags

Groups can be written through the API, so `process.jvm-flags` has an allowlist: a stolen `config:write` token must not become code execution on the node. See [config.md](config.md#jvm-flag-allowlist).

## Wiring in cloud-node

`cloud-api` does not depend on `cloud-node`. The node provides an `ApiBackend` (`ops.NodeApi`) and starts the server. `CloudNode` follows these steps; player events come from the channel's `NetworkPlayers`. A refusal of the service core reaches the client only if it is one of its own short reasons and holds nothing like a path; any other message stays in the node log and the client gets a short text. `ApiSettings.of` refuses `api.tls.enabled` without key material and key material with TLS off. `runs` in the wanted endpoints is `GroupRules.isWanted` of cloud-config, the rule the node itself plans by.

1. At start, after `ConfigService.open`: `ApiTokens tokens = ApiTokens.open(home)` and register `new ApiCommands(tokens).command()` in the console registry, so tokens can be created even while the API is off.
2. If `api.enabled`:
   ```java
   ApiConfig api = config.snapshot().cloud().api();
   // keystorePassword: VECTORA_API_KEYSTORE_PASSWORD from secrets/vectora.env, if set
   Optional<ApiTls.Material> tls = api.tls() != null && api.tls().enabled()
           ? Optional.of(ApiTls.prepare(home, api.tls(), api.bind(), keystorePassword))
           : Optional.empty();
   ApiServer server = ApiServer.builder(config, backend, tokens)
           .settings(ApiSettings.of(api, tls))
           .build();
   server.start();          // throws ApiStartException (an IOException) if it cannot bind
   ```
3. `ApiBackend` on top of the node:
   - `services()`: a `ServiceAccess` over `ServiceManager` (list, find, start by task, stop, restart, command), mapping a service to `ServiceView`. Failures are thrown as `ApiBackendException` with `NOT_FOUND`, `CONFLICT`, `UNAVAILABLE` or `INVALID`.
   - `node()`: `NodeStatus.ofThisJvm(nodeId, version, startedAt, shuttingDown, servicesTotal, servicesRunning)` and `shuttingDown()` of the node.
   - `backups()`: `Optional.of(new BackupServiceAccess(backupService))`.
   - `redact(String)`: the node's `RedactingFilter`.
4. Events: in the `ServiceListener`, `server.publish(new ApiEvent.ServiceState(view, previous))` on every state change and `server.publish(new ApiEvent.ServiceOutput(id, line))` for every output line; player join and quit from the bridges as `ApiEvent.Player`. `publish` never blocks.
5. Shutdown: `server.beginShutdown()` first in `CloudNode.close()` (refuses starts and reloads), `server.close()` last (closes streams with `1001`, then the sockets).

## Tests

`ApiServerTest`, `EventStreamTest`, `ApiTlsTest`, `ApiTokensTest`, `RateLimiterTest`, `RouterTest` and `ApiCommandsTest` run against a real server on `127.0.0.1` with a random port. Fuzz targets (Jazzer, see [architecture](architecture.md#fuzz-tests)):

- `RouterFuzzTest.resolveNeverThrowsAndOnlyPassesCleanValues`: any request target resolves without an exception, and a found route only carries parameters and query values from the strict alphabets.
- `ApiJsonFuzzTest.readFailsOnlyWithBadRequest`: any body becomes a value or fails with a `400` whose message has no control characters.

```sh
./gradlew :VectoraCloud:cloud-api:test --tests 'net.vectora.cloud.api.http.RouterFuzzTest.resolveNeverThrowsAndOnlyPassesCleanValues' -Pfuzz -PfuzzMaxDuration=5m
```
