// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.console;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.model.LogsConfig;
import org.jspecify.annotations.Nullable;

/**
 * A UTF-8 log file that rotates by day and by size and deletes old files after the retention of
 * {@code logs:} in {@code cloud.yml} ({@link LogsConfig}).
 *
 * <p>The current file is {@code <base>.log}. It is renamed to {@code <base>-<yyyy-MM-dd>.log}
 * (with {@code .1}, {@code .2} ... before {@code .log} when that name is taken) in three cases:
 * when the file is opened and an older one is there (one file per start, dated by its last change),
 * at the first line after midnight (dated by the day that ended), and at the first line after the
 * file reached {@code max-file-size}. A line is never split across two files, so a file may exceed
 * the limit by one line.
 *
 * <p>After every rename and when the file is opened, renamed files older than
 * {@code retention-days} days are deleted: dated files by the date in their name, files of the
 * former scheme ({@code <base>.<n>.log}, one per start) by their last change. The current file is
 * never deleted. The day comes from the {@link Clock} and its time zone, so tests use a fixed clock.
 *
 * <p>The settings are read at each rotation through a supplier, so a changed {@code logs:} takes
 * effect without a restart. Thread-safe; a failed rename leaves the lines in the current file.
 */
public final class RotatingLogFile extends Writer {

    /** File extension of every log file. */
    public static final String EXTENSION = ".log";

    private static final Pattern DATED = Pattern.compile("(.+)-(\\d{4}-\\d{2}-\\d{2})(?:\\.\\d{1,5})?\\.log");
    private static final Pattern NUMBERED = Pattern.compile("(.+)\\.\\d{1,5}\\.log");
    private static final int MAX_SUFFIX = 99_999;

    private final Path dir;
    private final String base;
    private final Path current;
    private final Supplier<LogsConfig> settings;
    private final Clock clock;
    private Writer out;
    private LocalDate day;
    private long size;
    private boolean atLineStart = true;
    private boolean closed;

    private RotatingLogFile(Path dir, String base, Supplier<LogsConfig> settings, Clock clock) {
        this.dir = dir;
        this.base = base;
        this.current = dir.resolve(base + EXTENSION);
        this.settings = settings;
        this.clock = clock;
        // Placeholders until openCurrent() runs in open(); the file is never written before that.
        this.out = nullWriter();
        this.day = LocalDate.EPOCH;
    }

    /**
     * Opens {@code <dir>/<base>.log}: an existing file is renamed first, old files are deleted.
     *
     * @param dir      the folder; created if missing, must not be a link
     * @param base     file name without {@code .log}, for example {@code cloud} or a service id
     * @param settings retention and file size, read at each rotation
     * @param clock    time and time zone of the dates
     * @return the open file
     * @throws IOException if the folder is a link or the file cannot be created
     */
    public static RotatingLogFile open(Path dir, String base, Supplier<LogsConfig> settings, Clock clock)
            throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        if (base == null || base.isEmpty() || base.contains("/") || base.contains("\\")) {
            throw new IllegalArgumentException("invalid log name");
        }
        RegularPaths.requireDirectoryOrMissing(dir);
        Files.createDirectories(dir);
        RotatingLogFile file = new RotatingLogFile(dir, base, settings, clock);
        if (Files.isRegularFile(file.current, LinkOption.NOFOLLOW_LINKS)) {
            LocalDate changed = Files.getLastModifiedTime(file.current, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .atZone(clock.getZone()).toLocalDate();
            file.archive(changed);
        } else {
            // A link or anything else in place of the log is removed, never followed.
            Files.deleteIfExists(file.current);
        }
        prune(dir, base, file.policy().retentionDays(), clock);
        file.openCurrent();
        return file;
    }

    /**
     * Deletes the renamed log files in a folder that are older than the retention.
     *
     * @param dir           the folder; nothing happens if it is missing
     * @param base          only files of this base name, or {@code null} for every base
     * @param retentionDays days to keep
     * @param clock         today and the time zone
     * @return number of deleted files
     * @throws IOException if the folder cannot be listed
     */
    public static int prune(Path dir, @Nullable String base, int retentionDays, Clock clock) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                LocalDate date = archiveDate(entry, base, clock);
                if (date != null && date.isBefore(cutoff) && Files.deleteIfExists(entry)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /** The day a renamed log file belongs to, or null if the file is not one of {@code base}. */
    private static @Nullable LocalDate archiveDate(Path file, @Nullable String base, Clock clock) throws IOException {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        Matcher dated = DATED.matcher(name);
        if (dated.matches() && (base == null || dated.group(1).equals(base))) {
            try {
                return LocalDate.parse(dated.group(2));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        Matcher numbered = NUMBERED.matcher(name);
        if (numbered.matches() && (base == null || numbered.group(1).equals(base))) {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().atZone(clock.getZone())
                    .toLocalDate();
        }
        return null;
    }

    /** The file that is written now. */
    public Path path() {
        return current;
    }

    @Override
    public void write(char[] chars, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, chars.length);
        synchronized (lock) {
            if (closed) {
                throw new IOException("log file closed");
            }
            int i = offset;
            int end = offset + length;
            while (i < end) {
                if (atLineStart) {
                    rotateIfDue();
                }
                int stop = i;
                while (stop < end && chars[stop] != '\n') {
                    stop++;
                }
                boolean lineEnds = stop < end;
                if (lineEnds) {
                    stop++;
                }
                out.write(chars, i, stop - i);
                size += utf8Length(chars, i, stop);
                atLineStart = lineEnds;
                i = stop;
            }
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (lock) {
            if (!closed) {
                out.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            out.close();
        }
    }

    private void rotateIfDue() throws IOException {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            rotate(day);
        } else if (size > 0 && size >= policy().maxFileBytes()) {
            rotate(today);
        }
    }

    private void rotate(LocalDate archiveDay) throws IOException {
        out.close();
        try {
            archive(archiveDay);
            prune(dir, base, policy().retentionDays(), clock);
        } catch (IOException | RuntimeException e) {
            // The lines go on into the current file; the next rotation tries again.
        }
        openCurrent();
    }

    private void archive(LocalDate archiveDay) throws IOException {
        String prefix = base + "-" + archiveDay;
        Path target = dir.resolve(prefix + EXTENSION);
        for (int n = 1; Files.exists(target, LinkOption.NOFOLLOW_LINKS); n++) {
            if (n > MAX_SUFFIX) {
                throw new IOException("too many log files for " + archiveDay);
            }
            target = dir.resolve(prefix + "." + n + EXTENSION);
        }
        Files.move(current, target);
    }

    private void openCurrent() throws IOException {
        out = Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        size = Files.size(current);
        day = LocalDate.now(clock);
    }

    private LogsConfig policy() {
        try {
            LogsConfig value = settings.get();
            return value == null ? LogsConfig.defaults() : value;
        } catch (RuntimeException e) {
            return LogsConfig.defaults();
        }
    }

    private static long utf8Length(char[] chars, int from, int to) {
        long bytes = 0;
        for (int i = from; i < to; i++) {
            char c = chars[i];
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 2; // with its low surrogate, 4 bytes
            } else if (Character.isLowSurrogate(c)) {
                bytes += 2;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
