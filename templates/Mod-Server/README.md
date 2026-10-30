# Mod server template

CraftAttack-style server with server-side Fabric mods only, so Bedrock players can join. Local port 25568.

Software: Fabric Loader 0.20.1 on MC 26.3 with Fabric API, FabricProxy-Lite, Floodgate-Fabric, LuckPerms-Fabric, Lithium, FerriteCore, ServerCore, Chunky, spark and ProxyPortal (ProxyPortal is built for 26.1, loads on 26.3 and will be replaced).

## Placeholders

- `{{VECTOLYN_SERVER_PORT}}`, `{{VECTOLYN_SERVER_HOST}}`, `{{VECTOLYN_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTOLYN_FORWARDING_SECRET}}` in `config/FabricProxy-Lite.toml`
- `{{VECTOLYN_DB_HOST}}`, `{{VECTOLYN_DB_PORT}}`, `{{VECTOLYN_DB_NAME}}`, `{{VECTOLYN_DB_USER}}`, `{{VECTOLYN_DB_PASSWORD}}` in `config/luckperms/luckperms.conf`
- `{{VECTOLYN_DB_STORAGE_METHOD}}` (`mariadb`, `mysql` or `h2`) and `{{VECTOLYN_DB_POOL_SIZE}}` (pool size set centrally by the cloud, normally 4) in `config/luckperms/luckperms.conf`
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- Generated file: `config/floodgate/key.pem` from `VECTOLYN_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectolynCloud setup: only servers marked as wanted are rendered from this template into their own folder under `local/services/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../placeholders.md`](../placeholders.md) for every placeholder.
