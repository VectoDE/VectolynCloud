# Database

`cloud/database` (`net.vectolyn.cloud.database`) prepares the database that LuckPerms and later the Vectolyn plugin use. It installs and secures a managed MariaDB, writes a compose file for Docker, checks an external server or picks the embedded H2 fallback. It also decides how many connections each service's pool may open.

The module does not start processes itself. In managed mode it says what to run, and `cloud/node` runs it through the `DatabaseProcesses` interface (see below).

## Packages

| Package | Content |
|---|---|
| `net.vectolyn.cloud.database` | `DatabaseProvisioner` and `DatabaseProvisioners` (one provisioner per mode), `DatabaseEndpoint`, `ConnectionBudget`, `DatabaseProcesses`, `DatabaseSecrets`, `HealthReport`, `StorageMethod`, `DatabaseException`, `ServerSetup`, `ExternalProvisioner`, `EmbeddedProvisioner` |
| `sql` | `SqlQuoting`, `BootstrapStatements` (all setup SQL), `ConnectionTarget`, `TlsMode`, `ServerVersion`, `SqlConnector`, `SqlSession`, `JdbcConnector` |
| `managed` | `ManagedMariaDbProvisioner`, `ManagedPlatform`, `MariaDbLayout`, `MariaDbConfigFile`, `MariaDbCommands`, `MariaDbBinaries`, `DownloadingMariaDbBinaries` |
| `docker` | `DockerProvisioner`, `DockerComposeFile` |

## Modes

`database.mode` in `config/cloud.yml` (see [configuration](config.md)) selects the provisioner. `DatabaseProvisioners.create(config, secrets, home, connector, managed)` returns it. Every provisioner has the same three calls:

- `provision()` brings the database into the wanted state and returns a `DatabaseEndpoint`. It is idempotent: a second call on a finished setup changes nothing.
- `health()` returns a `HealthReport` (reachable, latency, short detail).
- `shutdown()` stops what the cloud started. Only the managed mode starts something.

| Mode | What the cloud does | Who owns the server |
|---|---|---|
| `managed` | Downloads MariaDB, initialises the data directory, starts the server, secures it, creates database and user | the cloud |
| `docker` | Writes `compose.database.yml`, then waits for the container and sets up database and user | the operator (Docker) |
| `external` | Checks that the application user can log in and use its database. Never uses root. | the operator |
| `h2` | Nothing to start. LuckPerms keeps its data in a local H2 file. | LuckPerms |

### Managed MariaDB

Home: `<home>/database/`. It holds the unpacked server, `data/`, `vectolyn-mariadb.ini` and `mariadb.log`. The cloud writes the ini itself; there is no `Database` template in the home any more. Older homes (`Database/runtime/`) are moved here by the [home layout migration](node.md#home-layout-migration).

1. **Platform.** `ManagedPlatform.of(...)` accepts Windows x64 and Linux x86_64 with glibc, the two platforms MariaDB publishes server archives for. On every other platform (Linux on ARM or with musl, macOS, Windows on ARM) `DatabaseProvisioners.create` returns the embedded H2 fallback instead, so the cloud still starts. Its `notice()` names the platform and says how to get a shared database (`external` or `docker`); `cloud/node` logs it as a warning. `ManagedMariaDbProvisioner` used directly still fails there with `MANAGED_PLATFORM`.
2. **Binaries.** `DownloadingMariaDbBinaries` gets the archive from the `mariadb` provider in [downloads](download.md) (checksum, cache, safe extraction). It unpacks into a temporary folder and then moves it into place, so a broken download never leaves a half folder. `mariadb-install.properties` records folder, version and SHA-256. A matching install is reused.
3. **Config.** `MariaDbConfigFile` writes `vectolyn-mariadb.ini`: `bind-address=127.0.0.1`, `skip-name-resolve`, `max_connections=150`, `innodb_buffer_pool_size=256M`, `utf8mb4`, the port from `config/cloud.yml`. On Linux the socket goes to the temp directory. A test checks that every setting of the hand-written `templates/Database/vectolyn-mariadb.ini` is kept.
4. **Initialise.** Only when `data/mysql` is missing. Windows: `bin/mariadb-install-db.exe`, with `bin/mysql_install_db.exe` as fallback for older archives. Linux: `scripts/mariadb-install-db --no-defaults --auth-root-authentication-method=normal --skip-name-resolve`. A non-empty `data/` without `mysql/` is someone else's data. The cloud stops there instead of overwriting it.
5. **First start.** The server starts with `--init-file`. That file sets the root password before the server accepts its first connection, removes anonymous users and remote root accounts and drops the `test` database. It is written owner-only and deleted in a `finally` block, whether the start works or not. There is no window in which root has no password and accepts connections. Before `mariadb-install-db` runs, the cloud writes the marker `vectolyn-init.pending`; it is deleted only after root logged in with the generated password. If the first start fails (port taken, missing library), the next start sees the marker and passes the init file again, so the data directory never stays with a root account without password. A data directory without the marker (for example from the old start script) never gets the init file.
6. **Setup.** As root over `127.0.0.1`: secure again (same rules, for data directories from older setups; the grants of the `test` databases are matched by their bytes (`HEX(Db)`) instead of a literal backslash, so the statement means the same with `NO_BACKSLASH_ESCAPES` in `sql_mode`), then create the application database and user (see [rights](#rights)). Afterwards the application user logs in once to prove the grant works.
7. **Takeover.** If a server already answers on the port with the root password, the cloud uses it and only repeats step 6.
8. **Shutdown.** `SHUTDOWN` as root, then wait up to one minute, then kill.

Errors are `DatabaseException` with a `Reason` (`MANAGED_PLATFORM`, `INSTALL`, `INITIALIZE`, `START`, `UNREACHABLE`, `LOGIN`, `SETUP`, `CONNECTION_BUDGET`, `SETTINGS`). If the server exits during start, the message carries the tail of `mariadb.log`, passed through `RedactingFilter`.

**Linux requirements.** The MariaDB Linux archive links against `libaio` and `libncurses`. Install them with the package manager (`libaio1` or, on Ubuntu 24.04 and later, `libaio1t64`; `libncurses6`). Ubuntu 24.04 only ships `libaio.so.1t64`; the server looks for `libaio.so.1`, so it needs a symlink. On Linux every start error repeats this hint.

### Docker

The cloud does not talk to the Docker socket. It writes `<home>/database/compose.database.yml` (service `vectolyn-database`, image from `database.docker-image`, port bound to `127.0.0.1` only, data volume, health check, `--max-connections=150`). The root password comes from `secrets/vectolyn.env` at `docker compose` time and is never written into the file:

```
docker compose --env-file ../secrets/vectolyn.env -f compose.database.yml up -d
```

The cloud then waits 20 seconds for the container. If it does not answer, `provision()` fails with this command in the message. Once it answers, the cloud sets up database and user like in managed mode.

**Pterodactyl:** the cloud already runs inside a container there and has no Docker. Use `managed` (if the egg's image is glibc-based), `external` (a database from the panel) or `h2`. The setup assistant hides `docker` on Pterodactyl (`platform.pterodactyl`, see [launcher](launcher.md)).

### External

For a MariaDB or MySQL server the operator runs. The cloud only connects as the application user with `VECTOLYN_DB_PASSWORD`, runs `USE <database>` and `SELECT 1`, reads the server version and `SELECT @@max_connections`. The connection budget follows that value (`ConnectionBudget.forServer`: 20 connections kept free, or a fifth on a server with fewer than 100). If the value cannot be read, the managed budget (150) is assumed and `notice()` says so; the node logs it as a warning. A server that allows too few connections for even one pool fails the check with `CONNECTION_BUDGET`. Whether it is MariaDB or MySQL decides `VECTOLYN_DB_STORAGE_METHOD` (`mariadb` or `mysql`). The operator creates database and user with at least the [rights](#rights) below.

### H2 (embedded)

The embedded fallback: chosen with `database.mode: h2`, and used automatically when `managed` is configured on a platform without a MariaDB server build or with `servers.database: false` in `config/cloud.yml` (the database server is not wanted, so the node starts none). Good for tests and for a single small setup without any database server. LuckPerms ships its own H2 driver and file storage, so the cloud adds no dependency and starts nothing. Each service then has its own rank data; nothing is shared between servers. For a network, use one of the other modes.

H2 is dual-licensed under MPL 2.0 and EPL 1.0. MPL 2.0 allows combination with GPL code (section 3.3, "Secondary License"), so the choice is compatible with Vectolyn's GPL-3.0. Vectolyn does not distribute H2 anyway; LuckPerms downloads it at runtime.

## Rights

The application user (`database.user`, normally `luckperms`) gets exactly these rights, on its own database only:

```
SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
```

LuckPerms creates its tables and indexes on the first start (`CREATE`, `INDEX`) and afterwards only reads and writes rows. `ALTER` stays for schema migrations of later LuckPerms versions and the Vectolyn plugin. `DROP` is left out on purpose: it would let the user drop the whole database or `TRUNCATE` tables, and LuckPerms never drops a table. `REFERENCES` is left out because the schema has no foreign keys. No `GRANT OPTION`, no global rights, no `FILE`, no `PROCESS`, no access to `mysql.*`. Before granting, the cloud revokes everything else, so a user that had more rights from an older setup loses them. The hand-written `templates/Database/init.sql` grants the same list; a test compares both.

In managed and Docker mode the application user may log in from `127.0.0.1` (managed) or `%` (Docker, because the connection comes through the Docker network). Root keeps only the local hosts `localhost`, `127.0.0.1`, `::1` (plus `%` in Docker, where the container network requires it).

## SQL safety

All setup statements come from `BootstrapStatements`. Database names, users and hosts cannot be bound as JDBC parameters, so they go through `SqlQuoting`:

- Names: only `[A-Za-z0-9_]`, 1 to 64 characters, then in backticks. Anything else is refused, nothing is escaped.
- Hosts: `%`, IPv4, IPv6 or a host name, checked against a pattern.
- Passwords as string literals: `'` is doubled; backslashes and control characters are refused, so the result is safe whether or not the server runs with `NO_BACKSLASH_ESCAPES`. The generated secrets only use `[A-Za-z0-9._~+/=-]`.

The JDBC connection sets `allowMultiQueries=false` and `allowLocalInfile=false`. Unit tests and Jazzer fuzz tests (`SqlQuotingFuzzTest`, `ServerVersionFuzzTest`) check that no input breaks out of a quoted name or literal and that a server version string never throws anything but a parse failure.

## MariaDB Connector/J 4.0.0

The connection test and setup use `org.mariadb.jdbc:mariadb-java-client` 4.0.0 (released 2026-10-21), the version in the version catalog. Checked before use:

- Needs Java 17 or newer. The project runs on 21 and 25; the integration test runs on both.
- No required runtime dependencies.
- `sslMode` now defaults to `verify-full`. `ConnectionTarget` therefore sets it explicitly: `disable` only for loopback addresses (the managed server listens on `127.0.0.1` only), `verify-full` for every other host. An external server on another host needs a valid certificate.
- `PreparedStatement.executeQuery` is stricter about statements that return no result set. The module only uses plain `Statement`.
- Open issues at the time of the check: CONJ-1389 (an exception with `MAX_STATEMENT_TIME`, not used here) and CONJ-1388 (POM flattening, no effect on use).

Result: 4.0.0 is usable. The managed integration test proves it against a real MariaDB 11.8 on Windows and Linux. A fallback to 3.5.x was not needed.

## Pool sizes and placeholders

`ConnectionBudget` shares the server's connections between the services; `DatabaseProvisioner.connectionBudget()` returns the one of the mode. In managed and Docker mode: `max_connections` 150, 20 reserved for administration and the cloud, so 130 are available. Each service gets `min(4, available / services)`. Below 2 per service the cloud stops with `CONNECTION_BUDGET`, instead of starting servers that would fail later. With 4 per service, 32 services fit.

`DatabaseEndpoint.placeholderValues(poolSize)` returns the values for the templates:

| Placeholder | Value |
|---|---|
| `VECTOLYN_DB_HOST`, `VECTOLYN_DB_PORT`, `VECTOLYN_DB_NAME`, `VECTOLYN_DB_USER` | from the endpoint |
| `VECTOLYN_DB_STORAGE_METHOD` | `mariadb`, `mysql` or `h2` |
| `VECTOLYN_DB_POOL_SIZE` | from `ConnectionBudget`, normally `4` |

`minimum-idle` stays at 2 in every LuckPerms config. All placeholders: [`templates/placeholders.md`](../../templates/placeholders.md).

## Interface for cloud-node

`cloud/node` implements `DatabaseProcesses` and passes it in `DatabaseProvisioners.Managed`:

```java
int run(Command command, Duration timeout);   // mariadb-install-db, until it exits
Running start(Command command);               // mariadbd, long-running
record Command(List<String> arguments, Path workingDirectory, Path logFile);
interface Running { boolean isAlive(); boolean waitFor(Duration timeout); void kill(); }
```

The program in `arguments` is always an absolute path. Output goes to `logFile` (appended). The implementation is `NodeDatabaseProcesses` in `cloud/node`: no shell, the node's environment without `VECTOLYN_*` variables, and a program that does not finish within its timeout is killed with its child processes. `NodeDatabase` calls `provision()` before the first service starts, logs `notice()` as a warning and calls `shutdown()` after the last service stopped. It takes the pool size from `connectionBudget()` and the placeholder values from the endpoint. Details in [node.md](node.md#database).

## Tests

- Unit tests: config file, command lines, platforms, all setup SQL, quoting and injection attempts, connection URL and TLS choice, version parsing, budget, compose file (parsed as YAML), every provisioner against a fake SQL server and fake processes.
- Fuzz tests: `SqlQuotingFuzzTest`, `ServerVersionFuzzTest` (regression mode in every run, see [architecture](architecture.md#fuzz-tests)).
- Integration (`@Tag("mariadb")`, not part of `./gradlew build`): `./gradlew :cloud:database:mariadbTest` downloads MariaDB, starts it in a temp folder on a free port and checks bind address, `max_connections`, removed anonymous users, remote root and `test` database, the exact grants, refused wrong passwords, that the application user cannot create databases or users or read `mysql.user`, clean shutdown, restart with kept data, and that no password reaches the log. `-PmariadbVersion=11.4` picks another series. The workflow `.github/workflows/managed-mariadb.yml` runs it weekly on Linux and Windows with Java 21 and 25, and on pull requests that change this module.
