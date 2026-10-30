package net.vectolyn.cloud.backup.config;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import net.vectolyn.cloud.config.model.TimeSpan;

/**
 * Content of {@code <home>/backup.yml}. Every field except {@code schema-version} is optional; a
 * missing file means {@link #defaults()}.
 *
 * <pre>
 * schema-version: 1
 * directory: backups          # relative to the home, or an absolute folder
 * schedule:
 *   enabled: true
 *   cron: "0 4 * * *"         # or: interval: 6h (exactly one of both)
 * retention:
 *   keep-last: 7
 *   keep-daily: 7
 *   keep-weekly: 4
 *   keep-monthly: 6
 * save-timeout: 60s
 * compression-level: 6
 * database:
 *   enabled: true
 *   dump-executable: /usr/bin/mariadb-dump   # optional
 *   timeout: 10m
 * </pre>
 *
 * @param schemaVersion version of the file format, see {@link #SCHEMA_VERSION}
 * @param directory where the archives go: relative to the home or absolute
 * @param schedule automatic backups
 * @param retention which backups are kept
 * @param saveTimeout how long to wait for {@code save-off} and {@code save-all flush} of a running server
 * @param compressionLevel zip deflate level, 0 (store) to 9
 * @param database database dump settings
 */
@JsonPropertyOrder({"schema-version", "directory", "schedule", "retention", "save-timeout", "compression-level",
        "database"})
public record BackupConfig(int schemaVersion, String directory, ScheduleConfig schedule, RetentionConfig retention,
                           TimeSpan saveTimeout, Integer compressionLevel, DatabaseBackupConfig database) {

    /** Current version of the file format. */
    public static final int SCHEMA_VERSION = 1;
    /** Default folder, relative to the home. */
    public static final String DEFAULT_DIRECTORY = "backups";

    /** Fills missing sections with their defaults. */
    public BackupConfig {
        directory = directory == null ? DEFAULT_DIRECTORY : directory;
        schedule = schedule == null ? ScheduleConfig.disabled() : schedule;
        retention = retention == null ? RetentionConfig.defaults() : retention;
        saveTimeout = saveTimeout == null ? TimeSpan.ofSeconds(60) : saveTimeout;
        compressionLevel = compressionLevel == null ? 6 : compressionLevel;
        database = database == null ? DatabaseBackupConfig.defaults() : database;
    }

    /** No schedule, {@code backups/}, {@link RetentionConfig#defaults()}, 60 s save timeout, level 6, database on. */
    public static BackupConfig defaults() {
        return new BackupConfig(SCHEMA_VERSION, null, null, null, null, null, null);
    }

    /** A copy with another schedule. */
    public BackupConfig withSchedule(ScheduleConfig value) {
        return new BackupConfig(schemaVersion, directory, value, retention, saveTimeout, compressionLevel, database);
    }

    /** A copy with another retention. */
    public BackupConfig withRetention(RetentionConfig value) {
        return new BackupConfig(schemaVersion, directory, schedule, value, saveTimeout, compressionLevel, database);
    }

    /** A copy with other database settings. */
    public BackupConfig withDatabase(DatabaseBackupConfig value) {
        return new BackupConfig(schemaVersion, directory, schedule, retention, saveTimeout, compressionLevel, value);
    }

    /** A copy with another folder. */
    public BackupConfig withDirectory(String value) {
        return new BackupConfig(schemaVersion, value, schedule, retention, saveTimeout, compressionLevel, database);
    }
}
