# Minigame server template

[Deutsch](README.de.md)

One template for every minigame group until stage 6 brings the single games (BedWars, SkyWars and the rest). The cloud starts as many services from it as the group's scaling allows, each on its own port.

Software: Paper on the newest Minecraft version (today 26.3), the same build as the other backends. Plugins today: Floodgate, LuckPerms, TAB, PlaceholderAPI, WorldEdit, WorldGuard (the last five are replaced by Vectolyn modules in later stages).

Differences to the lobby: `gamemode=survival` and `spawn-protection=0`, because the games set the mode and the protected area themselves. No `VectolynLobby`.

## Placeholders

- `{{VECTOLYN_SERVER_PORT}}`, `{{VECTOLYN_SERVER_HOST}}` in `server.properties`
- `{{VECTOLYN_MANAGEMENT_SECRET}}` in `server.properties`
- `{{VECTOLYN_FORWARDING_SECRET}}` in `config/paper-global.yml`
- `{{VECTOLYN_DB_HOST}}`, `{{VECTOLYN_DB_PORT}}`, `{{VECTOLYN_DB_NAME}}`, `{{VECTOLYN_DB_USER}}`, `{{VECTOLYN_DB_PASSWORD}}` in `plugins/LuckPerms/config.yml`
- `{{VECTOLYN_DB_STORAGE_METHOD}}` and `{{VECTOLYN_DB_POOL_SIZE}}` in `plugins/LuckPerms/config.yml`. Minigame groups run many services at once, so the cloud shrinks the pool per service when the sum would exceed the database's connection limit.
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- `{{VECTOLYN_TAB_POWERED_BY}}` in `plugins/TAB/config.yml`: the last footer line "Powered by VectolynCloud" while `branding.powered-by` in `cloud.yml` is true (the default), nothing otherwise
- Generated file: `plugins/floodgate/key.pem` from `VECTOLYN_FLOODGATE_KEY_B64`

## Wanted or not

The setup asks for each minigame group whether it is wanted. Only wanted groups are rendered from this template into their own folder under `local/services/` and started, and only those appear in the proxy's server list. This README is documentation only; the cloud never copies it into a service folder. All placeholders: [`../placeholders.md`](../placeholders.md).
