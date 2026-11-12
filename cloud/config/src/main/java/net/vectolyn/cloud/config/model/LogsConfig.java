// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * How long the log files of the cloud ({@code logs/cloud.log}) and of the services
 * ({@code logs/services/<id>.log}) are kept.
 *
 * <p>A log file is closed and renamed to {@code <name>-<yyyy-MM-dd>.log} at each start, at
 * midnight and when it reaches {@code max-file-size}. Renamed files older than
 * {@code retention-days} days are deleted.
 *
 * @param retentionDays days a renamed log file is kept, {@value #MIN_RETENTION_DAYS} to
 *                      {@value #MAX_RETENTION_DAYS}
 * @param maxFileSize   size at which the current file is renamed and a new one begins
 */
@JsonPropertyOrder({"retention-days", "max-file-size"})
public record LogsConfig(int retentionDays, MemorySize maxFileSize) {

    /** Days kept when the file names none. */
    public static final int DEFAULT_RETENTION_DAYS = 14;
    /** Shortest retention. */
    public static final int MIN_RETENTION_DAYS = 1;
    /** Longest retention, ten years. */
    public static final int MAX_RETENTION_DAYS = 3650;
    /** File size when the file names none. */
    public static final MemorySize DEFAULT_MAX_FILE_SIZE = MemorySize.ofMegabytes(100);
    /** Largest {@code max-file-size} in MiB. */
    public static final long MAX_FILE_SIZE_MEGABYTES = 10L * 1024L;

    /** 14 days, 100 MiB per file. */
    public static LogsConfig defaults() {
        return new LogsConfig(DEFAULT_RETENTION_DAYS, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * Reads the section from the file. A missing field takes its default, so {@code logs: {}} and
     * {@code logs: {retention-days: 30}} are valid.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static LogsConfig fromFile(@JsonProperty("retention-days") Integer retentionDays,
                               @JsonProperty("max-file-size") MemorySize maxFileSize) {
        return new LogsConfig(retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays,
                maxFileSize == null ? DEFAULT_MAX_FILE_SIZE : maxFileSize);
    }

    /** A copy with another retention. */
    public LogsConfig withRetentionDays(int value) {
        return new LogsConfig(value, maxFileSize);
    }

    /** A copy with another file size. */
    public LogsConfig withMaxFileSize(MemorySize value) {
        return new LogsConfig(retentionDays, value);
    }

    /** {@code max-file-size} in bytes, the default if it is missing. */
    public long maxFileBytes() {
        return (maxFileSize == null ? DEFAULT_MAX_FILE_SIZE : maxFileSize).megabytes() * 1024L * 1024L;
    }
}
