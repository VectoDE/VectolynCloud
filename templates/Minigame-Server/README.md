# Minigame server template

[Deutsch](README.de.md)

One template for every minigame group until stage 6 brings the single games (BedWars, SkyWars and the rest). The cloud starts as many services from it as the group's scaling allows, each on its own port.

Software: Paper on the newest Minecraft version (today 26.3), the same build as the other backends. Plugins today: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (the last five are replaced by Vectora modules in later stages).

Differences to the lobby: `gamemode=survival` and `spawn-protection=0`, because the games set the mode and the protected area themselves. No `VectoraLobby`.

## Placeholders

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}` in `server.properties`
- `{{VECTORA_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTORA_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTORA_DB_STORAGE_METHOD}}` and `{{VECTORA_DB_POOL_SIZE}}` in `plugins/LuckPerms/config.yml`. Minigame groups run many services at once, so the cloud shrinks the pool per service when the sum would exceed the database's connection limit.
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Generated file: `plugins/floodgate/key.pem` from `VECTORA_FLOODGATE_KEY_B64`

## Wanted or not

The setup asks for each minigame group whether it is wanted. Only wanted groups are rendered from this template into `runtime/` and started, and only those appear in the proxy's server list. This README is documentation only; the cloud never copies it into `runtime/`. All placeholders: [`../placeholders.md`](../placeholders.md).
