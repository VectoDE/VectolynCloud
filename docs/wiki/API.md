# API

[Deutsch](API-DE)

VectolynCloud has a JSON API over HTTP with a WebSocket for live events. It is meant for scripts and for the dashboard that is planned for stage 8. The full reference is [docs/cloud/api.md](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/api.md); the machine-readable description is [api.openapi.yaml](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/api.openapi.yaml).

## Switching it on

The API is off by default. Set it in `config/cloud.yml`:

```yaml
api:
  enabled: true
  bind: 127.0.0.1
  port: 8085
```

Changes to `api:` apply at the next start. Any bind address other than loopback needs `api.tls.enabled: true`; the cloud refuses the configuration otherwise. With TLS it speaks TLS 1.3 and 1.2 and sends `Strict-Transport-Security`. Cross-origin access (CORS) is off until you list origins in `api.cors-origins`.

## Tokens and scopes

Every request needs a token; there is no anonymous endpoint. Create one in the cloud console:

```text
api token create dashboard services:read,config:read,node:read
```

The token starts with `vct_`, is shown once and is stored only as a SHA-256 hash. Send it as `Authorization: Bearer vct_...`. `api token list` and `api token revoke <name>` manage tokens; revoking works at once. A third argument gives the token a lifetime (`api token create ci services:read 90d`, 1h to 3650d); after it the token fails like a revoked one. A token with `admin` can do the same over `/api/v1/admin/tokens`.

| Scope | Allows |
|---|---|
| `services:read` | list and read services; service and player events |
| `services:write` | start, stop, restart, send a console command |
| `config:read` | read configuration, setup status |
| `config:write` | change groups, tasks, server switches, `cloud.yml` (not `api` and `database`), reload |
| `backups:read`, `backups:write` | list backups, create a backup |
| `node:read` | node status; metrics events |
| `admin` | everything, including the `api` and `database` sections and token management |

## Endpoints

All paths start with `/api/v1`.

| Path | What it does |
|---|---|
| `GET /services`, `POST /services`, `GET /services/{id}` | list, start one more service of a task, read one |
| `POST /services/{id}/stop`, `/restart`, `/command` | stop, restart, type a line into the console |
| `GET/PUT/DELETE /groups/{name}`, `PUT /groups/{name}/wanted` | groups |
| `GET/PUT/DELETE /tasks/{name}`, `PUT /tasks/{name}/wanted` | tasks |
| `GET /nodes`, `GET/PUT /nodes/{name}` | nodes |
| `GET/PUT /servers`, `PUT /servers/{kind}` | which servers are wanted |
| `GET/PUT /config`, `POST /config/reload`, `GET /config/warnings` | `cloud.yml` and reload |
| `GET /node`, `GET /setup` | node status, setup status |
| `GET/POST /backups` | list and create backups |
| `GET /events` (WebSocket) | live events: service state, service output, players, metrics (`node:read`), node log (`admin`) |
| `POST /graphql`, `GET /graphql` (WebSocket) | GraphQL over all of the above, subscriptions with `graphql-transport-ws` |

Writes are validated as a whole before anything is saved. `DELETE` needs `?confirm=<name>`. `GET` returns an `ETag`; send it back as `If-Match` to avoid overwriting someone else's change (`412` otherwise). Errors have one body shape with a `code`, a message and, for configuration problems, the file, path and line.

## Limits

Bodies are at most 256 KiB, JSON is read strictly, and there are rate limits per client address and per token (default 600 requests per minute per address and 300 per token). Every answer shows the token's budget in `X-RateLimit-Limit` and `X-RateLimit-Remaining`. Lists can be paged with `?limit=50`; the `Link` header points to the next page and `X-Total-Count` gives the size of the whole list. At most 32 event streams run at once. Slow stream clients lose events, which are then reported in one `dropped` event; they never slow the node down.

## Example

```sh
curl -s -H "Authorization: Bearer $VECTOLYN_TOKEN" http://127.0.0.1:8085/api/v1/services
```

## GraphQL

`POST /api/v1/graphql` answers queries and mutations for every area; each field checks the scope of the REST endpoint it stands for. Limits: depth 8, complexity 200, at most 20 data fields per document. Introspection is off unless you set `api.graphql-introspection: true`. Subscriptions (`events`) run over the WebSocket of the same path with `graphql-transport-ws`. Details: [API reference, GraphQL](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/api.md#graphql-post-apiv1graphql).

```sh
curl -s -H "Authorization: Bearer $VECTOLYN_TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"{ services { items { id state } } }"}' http://127.0.0.1:8085/api/v1/graphql
```

## Not available yet

gRPC and the web panel are planned ([#154](https://github.com/VectoDE/VectolynCloud/issues/154), [#151](https://github.com/VectoDE/VectolynCloud/issues/151)).
