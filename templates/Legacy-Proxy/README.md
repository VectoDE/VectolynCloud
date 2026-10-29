# Legacy proxy template

[Deutsch](README.de.md)

Second Velocity proxy, in front of the 1.8.8 groups (PandaSpigot, template [`Legacy-Server`](../Legacy-Server/README.md)). It runs with `player-info-forwarding-mode = "bungeeguard"`, because 1.8.8 servers only understand the old BungeeCord handshake. The main proxy stays on `modern`. Background: [docs/cloud/legacy-1.8.8.md](../../docs/cloud/legacy-1.8.8.md).

The cloud runs it only when `legacy-proxy.enabled` is `true` in `cloud.yml`. It listens on its own port (`legacy-proxy.port`, default 25564).

Software: Velocity, same version as the main proxy. No plugins yet. Whether Bedrock players reach the 1.8.8 groups through Geyser, Floodgate and Via* on this proxy is checked in stage 2, PR 24.

## Placeholders

- `{{VECTORA_PROXY_BIND}}`, `{{VECTORA_LEGACY_PROXY_PORT}}` in `velocity.toml` (bind address and query port)
- `{{VECTORA_PROXY_SERVERS}}`, `{{VECTORA_PROXY_TRY}}`, `{{VECTORA_PROXY_FORCED_HOSTS}}` in `velocity.toml`: the cloud fills them with the wanted 1.8.8 groups only, never with the backends of the main proxy
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- Generated file: `forwarding.secret`. The cloud writes `VECTORA_LEGACY_FORWARDING_SECRET` here, never `VECTORA_FORWARDING_SECRET` of the main proxy. Velocity sends it to the backends as `bungeeguard-token`, in plain text inside the handshake, so it must never equal the main proxy's modern-forwarding secret.

This README is documentation only; the cloud never copies it into `runtime/`. All placeholders: [`../placeholders.md`](../placeholders.md).
