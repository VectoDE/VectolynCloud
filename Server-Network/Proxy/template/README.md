# Proxy template

Entry point for Java and Bedrock players. Velocity with modern forwarding, Geyser and Floodgate for Bedrock, ViaVersion, ViaBackwards and ViaRewind so Java clients from 1.7.2/1.8 up to 26.3 can join.

Software: Velocity 4.3.0, Geyser-Velocity, Floodgate, LuckPerms-Velocity, ViaVersion 5.12.2, ViaBackwards 5.12.2, ViaRewind 4.2.0.

## Placeholders

- `{{VECTORA_PROXY_BIND}}`, `{{VECTORA_PROXY_PORT}}` in `velocity.toml`
- `{{VECTORA_PROXY_SERVERS}}`, `{{VECTORA_PROXY_TRY}}`, `{{VECTORA_PROXY_FORCED_HOSTS}}` in `velocity.toml`: filled from the wanted servers only
- `{{VECTORA_BEDROCK_PORT}}` in `plugins/Geyser-Velocity/config.yml`
- `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_PASSWORD}}` in `plugins/luckperms/config.yml`
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Generated files: `forwarding.secret` from `VECTORA_FORWARDING_SECRET`, `plugins/floodgate/key.pem` from `VECTORA_FLOODGATE_KEY_B64`

## Wanted or not

Whether this server exists at all is decided in the VectoraCloud setup: only servers marked as wanted are rendered from this template into `runtime/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../../placeholders.md`](../../placeholders.md) for every placeholder.
