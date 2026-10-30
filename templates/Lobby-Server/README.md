# Lobby server template

The hub every player lands on after joining the proxy. Local port 25566.

Software: Paper 26.3. Plugins today: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (the last five are replaced by Vectora modules in later stages). Also runs `VectoraLobby` and `VectoraPortalBridge` from this repository.

## Placeholders

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}` in `server.properties`
- `{{VECTORA_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTORA_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTORA_DB_STORAGE_METHOD}}` (`mariadb`, `mysql` or `h2`) and `{{VECTORA_DB_POOL_SIZE}}` (pool size set centrally by the cloud, normally 4) in `plugins/LuckPerms/config.yml`
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Generated file: `plugins/floodgate/key.pem` from `VECTORA_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectoraCloud setup: only servers marked as wanted are rendered from this template into their own folder under `local/services/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../placeholders.md`](../placeholders.md) for every placeholder.
