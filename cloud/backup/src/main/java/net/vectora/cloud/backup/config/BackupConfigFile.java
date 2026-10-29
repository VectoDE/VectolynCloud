package net.vectora.cloud.backup.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectora.cloud.backup.schedule.CronExpression;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.io.ConfigFormat;
import net.vectora.cloud.config.io.ConfigMappers;
import net.vectora.cloud.config.io.ConfigYaml;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.validation.ConfigNames;

/**
 * Reads and checks {@code <home>/backup.yml} with the strict reader of cloud-config (unknown
 * fields, duplicate keys and wrong types fail with file, field and line) and the same error model
 * ({@link ConfigError}, {@link ConfigException}).
 *
 * <p>The settings live in a file of their own instead of {@code cloud.yml}, so the backup module
 * can grow without changing the model every other module builds on. The file is optional: missing
 * means {@link BackupConfig#defaults()}. It is read again before every backup, so edits apply
 * without a restart.
 */
public final class BackupConfigFile {

    /** File name, relative to the home. */
    public static final String FILE = "backup.yml";
    /** Format with its schema version. */
    public static final ConfigFormat<BackupConfig> FORMAT =
            new ConfigFormat<>(BackupConfig.class, BackupConfig.SCHEMA_VERSION, List.of());

    /** Shortest interval. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(5);
    /** Shortest and longest save timeout. */
    public static final Duration MIN_SAVE_TIMEOUT = Duration.ofSeconds(5);
    /** Longest save timeout. */
    public static final Duration MAX_SAVE_TIMEOUT = Duration.ofMinutes(10);
    /** Shortest dump timeout. */
    public static final Duration MIN_DUMP_TIMEOUT = Duration.ofSeconds(10);
    /** Longest dump timeout. */
    public static final Duration MAX_DUMP_TIMEOUT = Duration.ofHours(6);

    private static final Pattern RELATIVE = Pattern.compile(ConfigNames.RELATIVE_PATH);
    private static final List<String> RESERVED = List.of("secrets", "groups", "tasks", "state", "logs", "cache");

    private static final String HEADER = """
            # Backup settings of VectoraCloud. Read again before every backup.
            # Comments are not kept when the cloud saves the file. Reference: docs/cloud/backup.md
            """;

    private final Path home;
    private final Path file;

    /**
     * The file of a home.
     *
     * @param home the cloud home
     */
    public BackupConfigFile(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.file = this.home.resolve(FILE);
    }

    /** {@code <home>/backup.yml}. */
    public Path file() {
        return file;
    }

    /**
     * Reads and checks the file.
     *
     * @return the settings; defaults when the file does not exist
     * @throws ConfigException with every problem found
     */
    public BackupConfig load() throws ConfigException {
        byte[] content;
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.NOT_REGULAR));
            }
            if (Files.size(file) > ConfigMappers.MAX_FILE_BYTES) {
                throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.FILE_TOO_LARGE, ConfigMappers.MAX_FILE_BYTES));
            }
            content = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return BackupConfig.defaults();
        } catch (IOException e) {
            throw new ConfigException(ConfigError.of(FILE, "", ErrorCode.FILE_ACCESS), e);
        }
        BackupConfig config = ConfigYaml.read(FILE, content, FORMAT).value();
        List<ConfigError> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return config;
    }

    /**
     * Writes {@link BackupConfig#defaults()} if the file does not exist yet, so operators find
     * every setting in one place.
     *
     * @return whether the file was written
     * @throws IOException if writing fails
     */
    public boolean writeIfMissing() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String written = new String(ConfigYaml.write(BackupConfig.defaults()), StandardCharsets.UTF_8);
        String body = written.substring(ConfigYaml.HEADER.length());
        new AtomicFileWriter().write(file, (HEADER + body).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * The backup folder: {@code directory} resolved against the home.
     *
     * @param config the settings
     * @return the absolute folder
     */
    public Path directory(BackupConfig config) {
        return home.resolve(config.directory()).toAbsolutePath().normalize();
    }

    /**
     * Every problem of the settings; empty when they are valid. Errors never quote values.
     *
     * @param config the settings
     * @return the problems
     */
    public static List<ConfigError> validate(BackupConfig config) {
        List<ConfigError> errors = new ArrayList<>();
        if (config.schemaVersion() != BackupConfig.SCHEMA_VERSION) {
            errors.add(ConfigError.of(FILE, "schema-version", ErrorCode.SCHEMA_VERSION_UNSUPPORTED,
                    BackupConfig.SCHEMA_VERSION));
        }
        validateDirectory(errors, config.directory());
        validateSchedule(errors, config.schedule());
        RetentionConfig retention = config.retention();
        range(errors, "retention.keep-last", retention.keepLast(), 1, RetentionConfig.MAX);
        range(errors, "retention.keep-daily", retention.keepDaily(), 0, RetentionConfig.MAX);
        range(errors, "retention.keep-weekly", retention.keepWeekly(), 0, RetentionConfig.MAX);
        range(errors, "retention.keep-monthly", retention.keepMonthly(), 0, RetentionConfig.MAX);
        span(errors, "save-timeout", config.saveTimeout(), MIN_SAVE_TIMEOUT, MAX_SAVE_TIMEOUT);
        range(errors, "compression-level", config.compressionLevel(), 0, 9);
        DatabaseBackupConfig database = config.database();
        span(errors, "database.timeout", database.timeout(), MIN_DUMP_TIMEOUT, MAX_DUMP_TIMEOUT);
        if (database.dumpExecutable() != null && !isAbsolutePath(database.dumpExecutable())) {
            errors.add(ConfigError.of(FILE, "database.dump-executable", ErrorCode.INVALID_VALUE,
                    "an absolute path to mariadb-dump or mysqldump"));
        }
        return List.copyOf(errors);
    }

    private static void validateDirectory(List<ConfigError> errors, String directory) {
        if (isAbsolutePath(directory)) {
            return;
        }
        if (!RELATIVE.matcher(directory).matches()) {
            errors.add(ConfigError.of(FILE, "directory", ErrorCode.INVALID_VALUE,
                    "a folder relative to the home (" + ConfigNames.RELATIVE_PATH + ") or an absolute path"));
            return;
        }
        String first = directory.split("/", 2)[0].toLowerCase(Locale.ROOT);
        if (RESERVED.contains(first)) {
            errors.add(ConfigError.of(FILE, "directory", ErrorCode.NOT_ALLOWED,
                    "it lies inside " + first + "/, which belongs to the cloud"));
        }
    }

    private static void validateSchedule(List<ConfigError> errors, ScheduleConfig schedule) {
        if (schedule.cron() != null && schedule.interval() != null) {
            errors.add(ConfigError.of(FILE, "schedule.interval", ErrorCode.NOT_ALLOWED, "schedule.cron is set"));
        }
        if (schedule.enabled() && schedule.cron() == null && schedule.interval() == null) {
            errors.add(ConfigError.of(FILE, "schedule.cron", ErrorCode.REQUIRED));
        }
        if (schedule.cron() != null) {
            try {
                CronExpression.parse(schedule.cron());
            } catch (IllegalArgumentException e) {
                errors.add(ConfigError.of(FILE, "schedule.cron", ErrorCode.INVALID_VALUE, CronExpression.FORMAT));
            }
        }
        if (schedule.interval() != null) {
            span(errors, "schedule.interval", schedule.interval(), MIN_INTERVAL, TimeSpan.MAX);
        }
    }

    private static void range(List<ConfigError> errors, String path, int value, int min, int max) {
        if (value < min || value > max) {
            errors.add(ConfigError.of(FILE, path, ErrorCode.OUT_OF_RANGE, min, max));
        }
    }

    private static void span(List<ConfigError> errors, String path, TimeSpan value, Duration min, Duration max) {
        Duration duration = value.duration();
        if (duration.compareTo(min) < 0 || duration.compareTo(max) > 0) {
            errors.add(ConfigError.of(FILE, path, ErrorCode.OUT_OF_RANGE, TimeSpan.ofSeconds(min.toSeconds()),
                    TimeSpan.ofSeconds(max.toSeconds())));
        }
    }

    private static boolean isAbsolutePath(String value) {
        if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
