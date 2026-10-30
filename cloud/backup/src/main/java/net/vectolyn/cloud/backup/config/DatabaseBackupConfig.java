package net.vectolyn.cloud.backup.config;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import net.vectolyn.cloud.config.model.TimeSpan;

/**
 * Database part of a backup.
 *
 * @param enabled whether backups contain a database dump
 * @param dumpExecutable absolute path of {@code mariadb-dump} or {@code mysqldump}; null means the
 *     managed server's own tool, otherwise the first one on {@code PATH}
 * @param timeout longest dump, 10 s to 6 h
 */
@JsonPropertyOrder({"enabled", "dump-executable", "timeout"})
public record DatabaseBackupConfig(Boolean enabled, String dumpExecutable, TimeSpan timeout) {

    /** Missing values: enabled, no fixed tool, 10 minutes. */
    public DatabaseBackupConfig {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        timeout = timeout == null ? TimeSpan.parse("10m") : timeout;
    }

    /** Enabled, tool found automatically, 10 minutes. */
    public static DatabaseBackupConfig defaults() {
        return new DatabaseBackupConfig(true, null, null);
    }
}
