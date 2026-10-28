# Mod server template

CraftAttack-style server with server-side Fabric mods only, so Bedrock players can join. Local port 25568.

Software: Fabric Loader 0.20.1 on MC 26.3 with Fabric API, FabricProxy-Lite, Floodgate-Fabric, LuckPerms-Fabric, Lithium, FerriteCore, ServerCore, Chunky, spark and ProxyPortal (ProxyPortal is built for 26.1, loads on 26.3 and will be replaced).

## Placeholders

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}`, `{{VECTORA_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTORA_FORWARDING_SECRET}}` in `config/FabricProxy-Lite.toml`
- `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_PASSWORD}}` in `config/luckperms/luckperms.conf`
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Generated file: `config/floodgate/key.pem` from `VECTORA_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectoraCloud setup: only servers marked as wanted are rendered from this template into `runtime/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../../placeholders.md`](../../placeholders.md) for every placeholder.
