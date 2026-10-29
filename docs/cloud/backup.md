# Backups

`VectoraCloud/cloud-backup` (`net.vectora.cloud.backup`) backs up the static services (worlds and configs from their `runtime/` folder) and the database into zip archives under `backups/`. It writes a manifest with a SHA-256 hash per file, prunes old archives, runs on a schedule and restores a single stopped service. It also defines the `backup` console command.

The module does not start or stop services. `cloud-node` hands it the static services and their control through three small interfaces (see [What cloud-node provides](#what-cloud-node-provides)). Until the node implements them, the module is complete but not wired in.

## Packages

| Package | Content |
|---|---|
| `net.vectora.cloud.backup` | `BackupService` (create, list, find, delete, prune, restore, `createBeforeVersionChange`), `BackupRequest`, `BackupReason`, `BackupInfo`, `RestoreResult`, `Retention`, `SaveCommands`, `BackupException`, and the interfaces `BackupTargets`, `ServiceControl`, `DatabaseSource` with `BackupTarget` |
| `config` | `BackupConfig` and its sections, `BackupConfigFile` (reads and validates `backup.yml`) |
| `manifest` | `BackupManifest`, `ManifestCodec` (strict reader and writer), `ManifestPaths` (safe archive paths), `ManifestException` |
| `archive` | `ArchiveWriter` (streams files into the zip and hashes them), `BackupFilter` (what never goes into a backup) |
| `database` | `DatabaseAccess`, `MariaDbDumper`, `H2Dumper`, `OptionFile`, `ToolRunner`, `ProcessToolRunner` |
| `schedule` | `CronExpression`, `BackupScheduler` |
| `command` | `BackupCommands` (`backup create|list|restore|delete`) |

## Archive format: zip

One backup is one file, `backups/vectora-backup-<id>.zip`. The id is the UTC time plus four random hex digits, for example `20261029-031500-3fa2`. Zip and not tar.gz, because:

- The JDK writes and reads zip itself (`ZipOutputStream`, `ZipFile`, Zip64 for archives over 4 GiB). There is no tar writer in the JDK, so tar.gz would need a new dependency or a hand-written writer.
- Zip has a central directory. `backup list` reads `manifest.json` straight from it without unpacking the archive. A tar.gz must be decompressed from the start to find a file.
- Restore unpacks with `SafeExtractor` from [cloud-download](download.md), which already guards zip archives against path traversal, absolute paths, links and zip bombs. Zip entries cannot be symbolic links at all.
- Windows opens zip files without extra tools, which helps when an operator has to fetch a single file by hand.

Worlds compress about equally well in both formats; region files are already compressed by Minecraft.

The archive is written as `.vectora-backup-<id>.zip.partial`, forced to disk and then renamed atomically. A crash never leaves a half archive with the final name. The archive is owner-only (POSIX `600`, on Windows an ACL for the current user only). So is the `backups/` folder, if the cloud created it.

Layout inside the archive:

```text
services/<service-id>/...           files of the service, relative to its runtime folder
database/luckperms.sql              dump of the managed or external MariaDB
database/<service-id>-luckperms-h2.sql   SCRIPT TO of a LuckPerms H2 file (h2 mode)
manifest.json                       always the last entry
```

## What goes into a backup

For every static service, everything under its runtime folder except:

- folders `logs`, `cache`, `crash-reports`, `debug`, `libraries`, `versions`, `bundler`, `.paper-remapped`, `.fabric`, `.mixin.out` and every folder called `secrets`;
- files `session.lock`, `.env`, `*.jar`, `*.log`, `*.log.gz`, `*.lock.db`, `*.trace.db`, `*.tmp`, and key or secret files (`*.secret`, `*.key`, `*.pem`, `*.p12`, `*.jks`, `*.env`). These come from the templates and `secrets/vectora.env` again, and a backup should not spread them.

Symbolic links are never followed and never stored. Files whose names would not unpack on Windows and Linux alike (reserved names such as `CON`, characters such as `:` or `\`, a trailing dot) are listed in the manifest under `skipped`. So are files that cannot be read.

### Consistency

| Service state | What happens | `consistency` in the manifest |
|---|---|---|
| stopped | files are copied directly; while the copy runs, the node must not start it | `offline` |
| running, a Minecraft server | `save-off`, then `save-all flush`, copy, then always `save-on` (also after errors) | `saved` |
| running, a proxy (no worlds) | copied as it is | `live` |
| starting or stopping | left out with a warning; a strict backup fails | `skipped` |
| no runtime folder yet | nothing to copy | `missing` |

The cloud waits for the confirmation line of each command (Paper and the 1.8.8 fork both print one), at most `save-timeout`. Without it, a scheduled backup leaves the service out with a warning, a strict one fails (`SAVE_FAILED`). Strict means: a backup of named services, the backup before a restore and the backup before a version change.

## Database

`database.mode` in `cloud.yml` decides what happens:

| Mode | Backup |
|---|---|
| `managed` | `mariadb-dump` (or `mysqldump`) from the installed server's `bin/` folder, as root, with routines, events and triggers |
| `external` | `mariadb-dump` or `mysqldump` from `PATH` (or `database.dump-executable`), as the application user |
| `docker` | no dump. The cloud prints the command to run on the host instead (below). `status: manual` |
| `h2` | per service: when the service is stopped, LuckPerms' H2 file is exported with `SCRIPT TO` through `org.h2.tools.Script` from the driver LuckPerms downloaded (`plugins/LuckPerms/libs/h2*.jar`), read-only. A running service only gets its files copied, with a warning. `status: per-service` |

The password is never a process argument. The cloud writes an option file (`[client] user, password, host, port, protocol=TCP`) into a private folder under `temp/backup/`, created owner-only and checked before use, passes it with `--defaults-extra-file` (the first option, otherwise the tool ignores it) and deletes it in a `finally` block. The dump runs with `--single-transaction --quick --skip-lock-tables --no-tablespaces --hex-blob --default-character-set=utf8mb4`, so LuckPerms keeps running. It only counts when the tool exits with 0 and the file ends with `-- Dump completed`. The tool's environment has no `VECTORA_*`, `MYSQL_PWD` or `MYSQL_HOST`, and its output is redacted before it shows up in a message.

Hint for Docker (the password stays a variable inside the container):

```bash
cd Server-Network/Database/runtime
docker compose --env-file ../../secrets/vectora.env -f compose.database.yml exec -T vectora-database \
  sh -c 'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb-dump -uroot --single-transaction --routines --events --triggers --databases luckperms' \
  > luckperms.sql
```

Restoring the database is manual on purpose: load the dump with `mariadb luckperms < luckperms.sql` (or `RUNSCRIPT FROM` for H2) while the network is stopped. An automatic database restore would overwrite permissions of all servers at once, also of those that were not restored.

## Manifest

`manifest.json`, schema version 1:

```json
{
  "schema-version": 1,
  "id": "20261029-031500-3fa2",
  "created-at": "2026-10-29T03:15:00Z",
  "reason": "scheduled",
  "cloud-version": "1.0.0",
  "note": null,
  "services": [
    {"id": "lobby-1", "group": "lobby", "minecraft-version": "26.3", "consistency": "saved"}
  ],
  "database": {"mode": "managed", "status": "dumped", "file": "database/luckperms.sql", "detail": "mariadb-dump"},
  "files": [
    {"path": "services/lobby-1/world/level.dat", "size": 1873, "sha256": "..."}
  ],
  "skipped": [
    {"path": "services/lobby-1/CON", "reason": "name is not portable"}
  ]
}
```

`reason` is `manual`, `scheduled`, `pre-restore` or `pre-update`. The Minecraft version comes from the group. `ManifestCodec.read` is strict: unknown fields, duplicate keys, more than 500 000 files, more than 128 MiB, unsafe paths (`..`, absolute, backslashes, device names, control characters), duplicate paths (also when they differ only in case), files outside a listed service, or a database entry that does not match its file all fail with `ManifestException`. The id must match the file name. A Jazzer fuzz test (`ManifestCodecFuzzTest`) checks that nothing else escapes and that every accepted manifest survives a round trip.

The hashes detect damage and an archive whose content does not match its manifest. They do not protect against someone who can write to `backups/`: such a person can change files and manifest together. Keep the folder owner-only and copy archives elsewhere if they must survive the machine.

## Restore

`backup restore <backup> <service>` (API: `BackupService.restore`) restores exactly one static service:

1. The node must report the service as stopped, and `ServiceControl.holdStopped` keeps it from starting until the restore is done. A running, starting or stopping service is refused (`SERVICE_RUNNING`, `SERVICE_BUSY`).
2. The manifest is read and checked (above). The archive must contain files of that service.
3. The archive is unpacked with `SafeExtractor` into an owner-only staging folder next to the runtime folder, with limits derived from the manifest.
4. Every unpacked file of the service is compared with the manifest: the same set of files, the same size and SHA-256. Any difference stops the restore (`HASH_MISMATCH` or `CORRUPT`) before anything is touched.
5. A safety backup of the current state is taken (`pre-restore`, without database). Its id is shown.
6. The runtime folder is swapped by renames: the current folder moves aside, the staged one takes its place, then the paths a backup never contains (excluded folders and files such as jars, logs and secrets, links, non-portable names) move back from the old folder. Then the old folder is deleted.

The database is not touched (see [Database](#database)).

## Retention

After every scheduled backup and after `backup create` without arguments, `prune()` deletes old archives. It keeps the union of:

- the newest `keep-last` backups;
- the newest backup of each of the last `keep-daily` days, `keep-weekly` ISO weeks and `keep-monthly` months (in the cloud's time zone).

Only `manual` and `scheduled` backups are pruned. Safety backups from a restore or a version change, and archives that cannot be read, stay until someone deletes them.

## Schedule

`schedule.cron` takes five fields (`minute hour day-of-month month day-of-week`, numbers, `*`, lists, ranges and steps; `0` and `7` are Sunday). If both day fields are set, either matches, as in cron. A time that does not exist because of the switch to daylight saving time is skipped. `schedule.interval` (5 minutes to 7 days) runs a backup once that time has passed since the newest scheduled backup (or since the start, if there is none). `BackupScheduler` checks every 30 seconds, reads `backup.yml` again before each run, and never runs two backups at once. Cron times missed while the cloud was off are not caught up; an overdue interval backup runs once, soon after the start.

## Configuration

`backup.yml` in the cloud home. A missing file means the defaults; `BackupConfigFile.writeIfMissing()` writes them with a comment header. It is read with the same strict reader and error messages as `cloud.yml` (see [configuration](config.md)), so the file lives on its own and the shared `cloud.yml` model stays unchanged.

```yaml
schema-version: 1
directory: backups
schedule:
  enabled: true
  cron: "0 4 * * *"
retention:
  keep-last: 7
  keep-daily: 7
  keep-weekly: 4
  keep-monthly: 6
save-timeout: 60s
compression-level: 6
database:
  enabled: true
  dump-executable: null
  timeout: 10m
```

| Key | Default | Rule |
|---|---|---|
| `schema-version` | `1` | must be 1 |
| `directory` | `backups` | relative to the home (not under `secrets/`, `groups/`, `tasks/`, `state/`, `logs/`, `cache/`) or absolute, for example a second disk. Only local folders; there are no paid cloud targets. |
| `schedule.enabled` | `false` | `true` needs `cron` or `interval` |
| `schedule.cron` | none | five fields, see above; not together with `interval` |
| `schedule.interval` | none | `5m` to `7d` |
| `retention.keep-last` | `7` | 1 to 1000 |
| `retention.keep-daily`, `keep-weekly`, `keep-monthly` | `7`, `4`, `6` | 0 to 1000 |
| `save-timeout` | `60s` | `5s` to `10m`, per save command |
| `compression-level` | `6` | 0 (store) to 9 |
| `database.enabled` | `true` | `false` backs up services only |
| `database.dump-executable` | none | absolute path to `mariadb-dump`/`mysqldump`, otherwise found automatically |
| `database.timeout` | `10m` | `10s` to `6h` |

## Console

| Command | Effect |
|---|---|
| `backup create [service ...]` | backs up all static services, or only the named ones, together with the database. Without arguments, prunes afterwards. |
| `backup list` | all archives, newest first, with reason, size, services and warnings; broken archives are marked |
| `backup restore <backup> <service> [--yes]` | restore as above, after a yes/no question (Enter means no) |
| `backup delete <backup> [--yes]` | deletes one archive, after a yes/no question |

Alias `backups`. Backup ids and service names complete with Tab. Texts exist in English and German. `BackupCommands.register(registry, service)` adds the command to the console's registry.

## What cloud-node provides

| Interface | Methods | Meaning |
|---|---|---|
| `BackupTargets` | `List<BackupTarget> staticServices()` | the static services: id, group, Minecraft version, runtime folder, and whether it saves worlds (Paper and 1.8.8: yes, proxies: no) |
| `ServiceControl` | `State state(id)` | `STOPPED`, `RUNNING` or `BUSY` (starting, stopping) |
| | `boolean execute(id, command, confirmation, timeout)` | sends a console command and waits for a log line that matches `confirmation`. It must listen before it sends. |
| | `Hold holdStopped(id)` | blocks starts of a stopped service until `close()`, fails with `BackupException` if it is not stopped |
| `DatabaseSource` | `Optional<DatabaseAccess> access()` | usually `DatabaseAccess.of(cloud.yml database section, secrets/vectora.env, installed MariaDB folder)` |

The node then builds one `BackupService`, starts a `BackupScheduler`, registers `BackupCommands` and may call `BackupConfigFile.writeIfMissing()` during setup.

### Backup before a Minecraft version change

`createBeforeVersionChange(group, fromVersion, toVersion)` backs up all static services of the group and the database, strictly: if a service cannot be saved or the dump fails, it throws and the version change must not go ahead. The archive has the reason `pre-update`, a note with group and versions, and is never pruned.

## Limits

- Restore unpacks the whole archive into the staging folder, so it needs free space for the archive's content once more.
- Database restore is manual (see above).
- Dynamic services (tasks) are not backed up. They start from their templates every time.
- The hashes are no signature (see [Manifest](#manifest)).

## Tests

- Unit tests in temp folders for creation, the save sequence, all consistency cases, filters, parallel operations (`BUSY`), list, delete, retention, schedule, cron, config validation, option file permissions, dump arguments, H2, Docker hint and the console command.
- Negative tests: path traversal and other unsafe paths in the archive, a manipulated manifest, hash and size mismatch, extra and missing files, a wrong manifest id, restore while the service runs or starts, wrong passwords, a dump without the final line, a database name that looks like an option.
- `ManifestCodecFuzzTest` with seed inputs under `src/test/resources`.
- `ManagedMariaDbBackupIT` (tag `mariadb`) downloads MariaDB, sets up a throwaway server on a free port in a temp folder, dumps it with the real `mariadb-dump` and checks the dump, that the password is not on the command line or in the archive, and that the option file is gone. It runs with `./gradlew :VectoraCloud:cloud-backup:mariadbTest`, weekly and on pull requests through `.github/workflows/managed-mariadb.yml`, on Linux and Windows with Java 21 and 25.
