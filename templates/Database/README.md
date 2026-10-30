# Database template

MariaDB 11.8 for the network's rank data (LuckPerms). Portable Windows build, listens on 127.0.0.1:3307.

`vectora-mariadb.ini` contains no paths: `start-vectora-database.cmd` passes `--basedir` and `--datadir` relative to its own folder, so the database starts wherever the repository lives. `max_connections` is 150; each LuckPerms instance uses a pool of at most 4 connections. `init.sql` creates the database and user on first setup, with only the rights LuckPerms needs.

These files serve the manual start until VectoraCloud takes over. In database mode `managed` the cloud writes `vectora-mariadb.ini` itself (same settings, always bound to 127.0.0.1), creates the data directory, sets the generated root password and runs the setup SQL on every start. Mode `docker` writes `compose.database.yml` into `database/` of the cloud home instead. The cloud does not copy this template into its home. Details: [docs/cloud/database.md](../../docs/cloud/database.md).

## Placeholders

- `{{VECTORA_DB_PORT}}`, `{{VECTORA_DB_HOST}}` in `vectora-mariadb.ini` and `start-vectora-database.cmd`
- `{{VECTORA_DB_NAME}}`, `{{VECTORA_DB_USER}}`, `{{VECTORA_DB_HOST}}`, `{{VECTORA_DB_PASSWORD}}` in `init.sql`

## Wanted or not

Whether the database runs is decided in the VectoraCloud setup (`database.mode` and `servers.database`), not by this template: the cloud runs its MariaDB from `database/` and writes the configuration itself. See [`../placeholders.md`](../placeholders.md) for every placeholder.
