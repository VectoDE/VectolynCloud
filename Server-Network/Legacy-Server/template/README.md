# Legacy server template (1.8.8)

[Deutsch](README.de.md)

Backend for groups that run Minecraft 1.8.8, for example a 1.8 PvP minigame. Software: PandaSpigot (group setting `software: pandaspigot`, `minecraft-version: "1.8.8"`), which runs on Java 21 (see [docs/cloud/legacy-1.8.8.md](../../../docs/cloud/legacy-1.8.8.md)). These servers sit behind the [legacy proxy](../../Legacy-Proxy/template/README.md), never behind the main proxy.

The template only holds what Vectora needs. PandaSpigot adds every other setting with its default value on the first start.

- `server.properties`: `online-mode=false`, bound to `VECTORA_SERVER_HOST`. Keep that a loopback or private address.
- `spigot.yml`: `settings.bungeecord: true`, so the server accepts the forwarding of the legacy proxy.
- `pandaspigot.yml`: `proxyOnlineMode: true`, matching `online-mode = true` of the legacy proxy.

PandaSpigot does not check the `bungeeguard-token` itself. Until the Vectora bridge does that (stage 2, PR 13), anyone who can reach a backend port can join with any name. Keep the ports closed to the outside.

## Placeholders

- `{{VECTORA_SERVER_PORT}}`, `{{VECTORA_SERVER_HOST}}` in `server.properties`
- `{{VECTORA_XMS}}`, `{{VECTORA_XMX}}` in `start.bat`
- No database placeholders yet: rank data on 1.8.8 comes with the Vectora plugin (stage 3).

## Wanted or not

Only groups marked as wanted are rendered from this template into `runtime/` and started. This README is documentation only; the cloud never copies it into `runtime/`. All placeholders: [`../../placeholders.md`](../../placeholders.md).
