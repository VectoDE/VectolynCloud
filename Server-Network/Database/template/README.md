# Database template

MariaDB 11.8 for the network's rank data (LuckPerms). Portable Windows build, listens on 127.0.0.1:3307.

`vectora-mariadb.ini` contains no paths: `start-vectora-database.cmd` passes `--basedir` and `--datadir` relative to its own folder, so the database starts wherever the repository lives. `max_connections` is 150; each LuckPerms instance uses a pool of at most 4 connections. `init.sql` creates the database and user on first setup.

## Placeholders

- `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_HOST}}` in `vectora-mariadb.ini` and `start-vectora-database.cmd`
- `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PASSWORD}}` in `init.sql`

## Wanted or not

Whether this server exists at all is decided in the VectoraCloud setup: only servers marked as wanted are rendered from this template into `runtime/` and started. An unwanted server is never created and is not referenced by the proxy or the lobby. See [`../../placeholders.md`](../../placeholders.md) for every placeholder.
