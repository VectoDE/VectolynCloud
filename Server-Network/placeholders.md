# Template placeholders

Every `Server-Network/<Server>/template/` holds configuration with placeholders of the form `{{VECTORA_NAME}}`. VectoraCloud (stage 2) renders a template into `runtime/` by replacing each placeholder with its value. Until then the existing `runtime/` folders are run directly through `start-network.bat`.

## Only wanted servers are rendered

In the VectoraCloud setup each of the seven servers (and later each minigame group) is marked as wanted or not. The cloud renders and starts only wanted servers. A server that is not wanted is never created, and neither the proxy nor the lobby references it. That is why nothing in a template points to another server by a fixed name or port: the proxy's server list, its `try` order and its forced hosts are placeholders that the cloud fills from the wanted servers only, and every backend gets its port from `VECTORA_SERVER_PORT`.

## Allowed values

No value may contain a line break or another control character, except `VECTORA_PROXY_SERVERS` and `VECTORA_PROXY_FORCED_HOSTS`. Secrets (`*_SECRET`, `*_PASSWORD`) need at least 16 characters from `A-Z a-z 0-9 . _ ~ + / = -`, ports (`*_PORT`) are numbers from 1 to 65535. The cloud refuses to render anything else. Details in [`docs/cloud/template.md`](../docs/cloud/template.md#allowed-values).

## Global values

These come from `Server-Network/secrets/vectora.env` (gitignored, see `secrets/vectora.env.example`).

| Placeholder | Meaning | Used in |
|---|---|---|
| `VECTORA_FORWARDING_SECRET` | Velocity modern-forwarding secret. Must be identical on the proxy and every backend. | `config/paper-global.yml` (Paper), `config/FabricProxy-Lite.toml` (Mod), and the generated `Proxy/runtime/forwarding.secret` |
| `VECTORA_DB_HOST` | Host of the MariaDB server, also the host part of the LuckPerms DB user. | LuckPerms configs, `vectora-mariadb.ini`, `init.sql` |
| `VECTORA_DB_PORT` | MariaDB port (3307 locally). | LuckPerms configs, `vectora-mariadb.ini`, DB start script |
| `VECTORA_DB_NAME` | LuckPerms database name. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_USER` | LuckPerms database user. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_PASSWORD` | Password of `VECTORA_DB_USER`. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_ROOT_PASSWORD` | Password of the MariaDB `root` user. Only for administration and the cloud's database setup (for example running `init.sql`). | no server template |
| `VECTORA_MANAGEMENT_SECRET` | `management-server-secret` in `server.properties` (40 alphanumeric characters). The management server itself stays disabled. | `server.properties` (Paper, Mod) |
| `VECTORA_FLOODGATE_KEY_B64` | Floodgate AES key (16 raw bytes), base64 encoded. Not a text placeholder: the cloud decodes it into `key.pem`. | generated `plugins/floodgate/key.pem` (Proxy, Paper), `config/floodgate/key.pem` (Mod) |

## Per-service values

The cloud assigns these for each service it creates.

| Placeholder | Meaning | Current local value |
|---|---|---|
| `VECTORA_SERVER_PORT` | Port the backend listens on. | Lobby 25566, Survival 25567, Mod 25568, Creative 25569, End 25570 |
| `VECTORA_SERVER_HOST` | Address the backend binds to. Keep it on loopback or a private network: backends run in offline mode behind Velocity. | `127.0.0.1` |
| `VECTORA_XMS` | Initial Java heap, e.g. `512M`. | Lobby 256M, Survival 512M, Mod 512M, Creative 256M, End 256M, Proxy 256M |
| `VECTORA_XMX` | Maximum Java heap. | Lobby 768M, Survival 1536M, Mod 1536M, Creative 512M, End 512M, Proxy 512M |

## Proxy values

| Placeholder | Meaning | Current local value |
|---|---|---|
| `VECTORA_PROXY_BIND` | Address the Java listener binds to. | `0.0.0.0` |
| `VECTORA_PROXY_PORT` | Java port of the proxy (also the query port). | `25565` |
| `VECTORA_BEDROCK_PORT` | Geyser Bedrock port (UDP, plus TCP for built-in signaling). | `19132` |
| `VECTORA_PROXY_SERVERS` | One TOML line per wanted backend: `name = "host:port"`. | `lobby = "127.0.0.1:25566"` and so on |
| `VECTORA_PROXY_TRY` | Comma-separated, quoted server names tried on login, e.g. `"lobby"`. | `"lobby"` |
| `VECTORA_PROXY_FORCED_HOSTS` | One TOML line per forced host: `"host" = [ "server" ]`. May be empty. | `"creative.deine-domain.de" = [ "creative" ]` (placeholder domain) |

## Files the cloud creates instead of copying

- `Proxy/runtime/forwarding.secret` from `VECTORA_FORWARDING_SECRET`.
- `key.pem` for Floodgate from `VECTORA_FLOODGATE_KEY_B64` (same file on the proxy and on every backend, including the Fabric Mod-Server).
- `eula.txt` only after the operator accepted the Minecraft EULA in the setup assistant.
- Server jars, plugin and mod jars, MariaDB binaries: downloaded and checksum-verified by the cloud, never stored in the repository.

## Not in templates

Worlds, logs, caches, libraries, LuckPerms H2 files, `usercache.json`, `ops.json`, `whitelist.json`, `banned-*.json`, bStats server IDs, Geyser's `nethernet/identity.pem`, TAB player data and the MariaDB data directory stay in `runtime/` only.
