package net.vectora.cloud.backup.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.TimeSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupConfigFileTest {

    @TempDir
    Path home;

    private BackupConfig load(String yaml) throws ConfigException {
        try {
            Files.writeString(home.resolve("backup.yml"), yaml, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return new BackupConfigFile(home).load();
    }

    private List<ConfigError> errors(String yaml) {
        return assertThrows(ConfigException.class, () -> load(yaml)).errors();
    }

    @Test
    void missingFileMeansDefaults() throws Exception {
        BackupConfig config = new BackupConfigFile(home).load();
        assertEquals(BackupConfig.defaults(), config);
        assertFalse(config.schedule().enabled());
        assertEquals(7, config.retention().keepLast());
        assertEquals(home.resolve("backups"), new BackupConfigFile(home).directory(config));
    }

    @Test
    void fullFileIsRead() throws Exception {
        BackupConfig config = load("""
                schema-version: 1
                directory: data/backups
                schedule:
                  enabled: true
                  cron: "0 4 * * *"
                retention:
                  keep-last: 3
                  keep-daily: 7
                  keep-weekly: 4
                  keep-monthly: 12
                save-timeout: 2m
                compression-level: 1
                database:
                  enabled: false
                  timeout: 30m
                """);
        assertEquals("0 4 * * *", config.schedule().cron());
        assertEquals(new RetentionConfig(3, 7, 4, 12), config.retention());
        assertEquals(TimeSpan.parse("2m"), config.saveTimeout());
        assertEquals(1, config.compressionLevel());
        assertFalse(config.database().enabled());
        assertEquals(home.resolve("data/backups"), new BackupConfigFile(home).directory(config));
    }

    @Test
    void partialSectionsGetDefaults() throws Exception {
        BackupConfig config = load("""
                schema-version: 1
                schedule:
                  enabled: true
                  interval: 6h
                retention:
                  keep-last: 2
                """);
        assertEquals(TimeSpan.parse("6h"), config.schedule().interval());
        assertEquals(new RetentionConfig(2, 0, 0, 0), config.retention());
        assertTrue(config.database().enabled());
    }

    @Test
    void absoluteDirectoryIsAllowed() throws Exception {
        Path target = home.resolve("elsewhere").toAbsolutePath();
        BackupConfig config = load("schema-version: 1\ndirectory: '" + target + "'\n");
        assertEquals(target, new BackupConfigFile(home).directory(config));
    }

    @Test
    void strictReaderRejectsTyposAndWrongTypes() {
        assertEquals(ErrorCode.UNKNOWN_FIELD, errors("schema-version: 1\nretension: {}\n").getFirst().code());
        assertEquals(ErrorCode.INVALID_VALUE, errors("schema-version: 1\ncompression-level: '6'\n").getFirst().code());
        assertEquals(ErrorCode.SCHEMA_VERSION_MISSING, errors("directory: x\n").getFirst().code());
        assertEquals(ErrorCode.DUPLICATE_FIELD, errors("schema-version: 1\ndirectory: a\ndirectory: b\n").getFirst().code());
    }

    @Test
    void scheduleNeedsExactlyOneOfCronAndInterval() {
        assertEquals(ErrorCode.REQUIRED, errors("schema-version: 1\nschedule: {enabled: true}\n").getFirst().code());
        ConfigError both = errors("schema-version: 1\nschedule: {enabled: true, cron: '0 4 * * *', interval: 1h}\n")
                .getFirst();
        assertEquals(ErrorCode.NOT_ALLOWED, both.code());
        assertEquals("schedule.interval", both.path());
    }

    @Test
    void invalidValuesAreReportedWithoutTheValue() {
        List<ConfigError> errors = errors("""
                schema-version: 1
                directory: ../outside
                schedule: {enabled: true, cron: '61 4 * * *'}
                retention: {keep-last: 0, keep-daily: 1001}
                save-timeout: 1s
                compression-level: 10
                database: {timeout: 5s, dump-executable: relative/mysqldump}
                """);
        List<String> paths = errors.stream().map(ConfigError::path).toList();
        assertEquals(List.of("directory", "schedule.cron", "retention.keep-last", "retention.keep-daily", "save-timeout",
                "compression-level", "database.timeout", "database.dump-executable"), paths);
        for (ConfigError error : errors) {
            assertFalse(error.toString().contains("outside") || error.toString().contains("61 4"), error.toString());
        }
    }

    @Test
    void intervalOutOfRangeAndReservedFolders() {
        assertEquals(ErrorCode.OUT_OF_RANGE,
                errors("schema-version: 1\nschedule: {enabled: true, interval: 1m}\n").getFirst().code());
        assertEquals(ErrorCode.NOT_ALLOWED, errors("schema-version: 1\ndirectory: secrets/backups\n").getFirst().code());
        assertEquals(ErrorCode.NOT_ALLOWED, errors("schema-version: 1\ndirectory: Groups\n").getFirst().code());
    }

    @Test
    void writeIfMissingWritesLoadableDefaultsOnce() throws Exception {
        BackupConfigFile file = new BackupConfigFile(home);
        assertTrue(file.writeIfMissing());
        String written = Files.readString(file.file());
        assertTrue(written.startsWith("# Backup settings"), written);
        assertEquals(BackupConfig.defaults(), file.load());
        assertFalse(file.writeIfMissing(), "never overwrites");
    }
}
