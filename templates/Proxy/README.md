# Proxy template

Entry point for Java and Bedrock players. Velocity with modern forwarding, Geyser and Floodgate for Bedrock, ViaVersion, ViaBackwards and ViaRewind so Java clients from 1.7.2/1.8 up to 26.3 can join.

Software: Velocity 4.3.0, Geyser-Velocity, Floodgate, LuckPerms-Velocity, ViaVersion 5.12.2, ViaBackwards 5.12.2, ViaRewind 4.2.0.

## Placeholders

- `{{VECTOLYN_PROXY_BIND}}`, `{{VECTOLYN_PROXY_PORT}}` in `velocity.toml`
- `{{VECTOLYN_PROXY_SERVERS}}`, `{{VECTOLYN_PROXY_TRY}}`, `{{VECTOLYN_PROXY_FORCED_HOSTS}}` in `velocity.toml`: filled from the wanted servers only
- `{{VECTOLYN_BEDROCK_PORT}}`, `{{VECTOLYN_BEDROCK_NETHERNET_PORT}}` in `plugins/Geyser-Velocity/config.yml` (Geyser runs `transport: both`: RakNet and NetherNet)
- `{{VECTOLYN_DB_HOST}}`, `{{VECTOLYN_DB_PORT}}`, `{{VECTOLYN_DB_NAME}}`, `{{VECTOLYN_DB_USER}}`, `{{VECTOLYN_DB_PASSWORD}}` in `plugins/luckperms/config.yml`
- `{{VECTOLYN_DB_STORAGE_METHOD}}` (`mariadb`, `mysql` or `h2`) and `{{VECTOLYN_DB_POOL_SIZE}}` (pool size set centrally by the cloud, normally 4) in `plugins/luckperms/config.yml`
- `{{VECTOLYN_XMS}}`, `{{VECTOLYN_XMX}}` in `start.bat`
- Generated files: `forwarding.secret` from `VECTOLYN_FORWARDING_SECRET`, `plugins/floodgate/key.pem` from `VECTOLYN_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectolynCloud setup: only servers marked as wanted are rendered from this template into their own folder under `local/services/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../placeholders.md`](../placeholders.md) for every placeholder.
