# Template placeholders

Every template `templates/<Server>/` (in the cloud home `local/templates/<Server>/default/`) holds configuration with placeholders of the form `{{VECTORA_NAME}}`. VectoraCloud (stage 2) renders a template into the folder of a service (`local/services/<id>`, or `temp/services/<id>_<run>` for a dynamic service) by replacing each placeholder with its value. Until then the existing `runtime/` folders of the manual setup are run directly through `start-network.bat` (`cloud/dist/manual/`).

## Only wanted servers are rendered

In the VectoraCloud setup each of the seven servers and each minigame group is marked as wanted or not; the legacy proxy and its 1.8.8 groups only run when `legacy-proxy.enabled` is set. The cloud renders and starts only wanted servers. A server that is not wanted is never created, and neither the proxy nor the lobby references it. That is why nothing in a template points to another server by a fixed name or port: the proxy's server list, its `try` order and its forced hosts are placeholders that the cloud fills from the wanted servers only, and every backend gets its port from `VECTORA_SERVER_PORT`.

## Allowed values

No value may contain a line break or another control character, except `VECTORA_PROXY_SERVERS` and `VECTORA_PROXY_FORCED_HOSTS`. Secrets (`*_SECRET`, `*_PASSWORD`) need at least 16 characters from `A-Z a-z 0-9 . _ ~ + / = -`, ports (`*_PORT`) are numbers from 1 to 65535. The cloud refuses to render anything else. Details in [`docs/cloud/template.md`](../docs/cloud/template.md#allowed-values).

## Global values

These come from `secrets/vectora.env` in the cloud home (never in the repository, see [`cloud/dist/secrets/vectora.env.example`](../cloud/dist/secrets/vectora.env.example)).

| Placeholder | Meaning | Used in |
|---|---|---|
| `VECTORA_FORWARDING_SECRET` | Velocity modern-forwarding secret. Must be identical on the proxy and every backend. | `config/paper-global.yml` (Paper), `config/FabricProxy-Lite.toml` (Mod), and the generated `forwarding.secret` of the proxy (`local/services/proxy-1/`) |
| `VECTORA_LEGACY_FORWARDING_SECRET` | Forwarding secret of the legacy proxy (`bungeeguard` token for the 1.8.8 servers). Generated on its own and never equal to `VECTORA_FORWARDING_SECRET`. | the generated `forwarding.secret` of the legacy proxy (`local/services/legacy-proxy-1/`) |
| `VECTORA_DB_HOST` | Host of the MariaDB server, also the host part of the LuckPerms DB user. | LuckPerms configs, `vectora-mariadb.ini`, `init.sql` |
| `VECTORA_DB_PORT` | MariaDB port (3307 locally). | LuckPerms configs, `vectora-mariadb.ini`, DB start script |
| `VECTORA_DB_NAME` | LuckPerms database name. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_USER` | LuckPerms database user. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_PASSWORD` | Password of `VECTORA_DB_USER`. | LuckPerms configs, `init.sql` |
| `VECTORA_DB_STORAGE_METHOD` | LuckPerms `storage-method`: `mariadb` (managed, Docker or external MariaDB), `mysql` (external MySQL) or `h2` (database mode `h2`: embedded files per server, the fallback without a database server). Set by the cloud from the database mode, not from `vectora.env`. | LuckPerms configs |
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
| `VECTORA_DB_POOL_SIZE` | `maximum-pool-size` of the LuckPerms connection pool. The cloud sizes it centrally so that all pools together stay below the database's `max_connections` (150 for the managed server, 20 of them kept free): 4 while that fits, smaller when many services run, never below 2 (`minimum-idle`). | 4 |

## Proxy values

| Placeholder | Meaning | Current local value |
|---|---|---|
| `VECTORA_PROXY_BIND` | Address the Java listener binds to. | `0.0.0.0` |
| `VECTORA_PROXY_PORT` | Java port of the proxy (also the query port). | `25565` |
| `VECTORA_BEDROCK_PORT` | Geyser Bedrock port: UDP for RakNet, TCP for NetherNet built-in signaling. | `19132` |
| `VECTORA_BEDROCK_NETHERNET_PORT` | UDP port of Geyser's NetherNet (WebRTC) traffic, always `bedrock-port` + 1. | `19133` |
| `VECTORA_PROXY_SERVERS` | One TOML line per wanted backend: `name = "host:port"`. | `lobby = "127.0.0.1:25566"` and so on |
| `VECTORA_PROXY_TRY` | Comma-separated, quoted server names tried on login, e.g. `"lobby"`. | `"lobby"` |
| `VECTORA_PROXY_FORCED_HOSTS` | One TOML line per forced host: `"host" = [ "server" ]`. May be empty. | `"creative.deine-domain.de" = [ "creative" ]` (placeholder domain) |

## Legacy proxy values

The [legacy proxy](Legacy-Proxy/README.md) uses the proxy values above (`VECTORA_PROXY_BIND`, `VECTORA_PROXY_SERVERS`, `VECTORA_PROXY_TRY`, `VECTORA_PROXY_FORCED_HOSTS`), filled with the 1.8.8 groups only, plus its own port:

| Placeholder | Meaning | Current local value |
|---|---|---|
| `VECTORA_LEGACY_PROXY_PORT` | Java port of the legacy proxy (also its query port), `legacy-proxy.port` in `cloud.yml`. | `25564` |

Its `forwarding.secret` holds `VECTORA_LEGACY_FORWARDING_SECRET`, never `VECTORA_FORWARDING_SECRET`: in `bungeeguard` mode Velocity sends it to the backends in plain text.

## Templates

| Folder | Software | `ServerPlatform` |
|---|---|---|
| `Proxy` | Velocity, modern forwarding | `VELOCITY` |
| `Lobby-Server`, `Survival-Server`, `Creative-Server`, `End-Server` | Paper | `PAPER` |
| `Mod-Server` | Fabric | `FABRIC` |
| `Minigame-Server` | Paper, one template for every minigame group until stage 6 | `PAPER` |
| `Database` | MariaDB (manual start; the managed mode writes its own config) | `NONE` |
| `Legacy-Proxy` | Velocity, `bungeeguard` forwarding, in front of the 1.8.8 groups | `VELOCITY_LEGACY` (own forwarding secret, no Floodgate key) |
| `Legacy-Server` | PandaSpigot 1.8.8, behind the legacy proxy | `PANDASPIGOT` (EULA only, no Floodgate key) |

The new templates (`Minigame-Server`, `Legacy-Proxy`, `Legacy-Server`) have a `README.md` in English and a `README.de.md` in German. Markdown files are never copied into a service folder. Service folders live in the cloud home, never in the repository.

## Files the cloud creates instead of copying

- `forwarding.secret` of the proxy from `VECTORA_FORWARDING_SECRET`; `forwarding.secret` of the legacy proxy from `VECTORA_LEGACY_FORWARDING_SECRET` (both in the service folder, for example `local/services/proxy-1/`).
- `key.pem` for Floodgate from `VECTORA_FLOODGATE_KEY_B64` (same file on the proxy and on every backend, including the Fabric Mod-Server). The legacy proxy and the 1.8.8 servers get none, because Floodgate does not run there (yet).
- `eula.txt` only after the operator accepted the Minecraft EULA in the setup assistant.
- Server jars, plugin and mod jars, MariaDB binaries: downloaded and checksum-verified by the cloud, never stored in the repository.

## Not in templates

Worlds, logs, caches, libraries, LuckPerms H2 files, `usercache.json`, `ops.json`, `whitelist.json`, `banned-*.json`, bStats server IDs, Geyser's `nethernet/identity.pem`, TAB player data and the MariaDB data directory stay in the service folder (`local/services/<id>`, the data directory in `database/`) only.
