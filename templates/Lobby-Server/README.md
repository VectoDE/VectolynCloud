# Lobby server template

The hub every player lands on after joining the proxy. Local port 25566.

Software: Paper 26.3. Plugins today: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (the last five are replaced by Vectolyn modules in later stages). Also runs `VectolynLobby` and `VectolynPortalBridge` from this repository.

## Placeholders

- `{{VECTOLYN_SERVER_PORT}}`, `{{VECTOLYN_SERVER_HOST}}` in `server.properties`
- `{{VECTOLYN_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTOLYN_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTOLYN_DB_HOST}}`, `{{VECTOLYN_DB_PORT}}`, `{{VECTOLYN_DB_NAME}}`, `{{VECTOLYN_DB_USER}}`, `{{VECTOLYN_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTOLYN_DB_STORAGE_METHOD}}` (`mariadb`, `mysql` or `h2`) and `{{VECTOLYN_DB_POOL_SIZE}}` (pool size set centrally by the cloud, normally 4) in `plugins/LuckPerms/config.yml`
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- Generated file: `plugins/floodgate/key.pem` from `VECTOLYN_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectolynCloud setup: only servers marked as wanted are rendered from this template into their own folder under `local/services/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../placeholders.md`](../placeholders.md) for every placeholder.
