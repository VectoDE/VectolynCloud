package net.vectora.cloud.console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The one log line format of the cloud, on the console, before the console is up and in
 * {@code logs/cloud.log}: {@code HH:mm:ss LEVEL message}. The level names are fixed English words of
 * the same width ({@code ERROR}, {@code WARN }, {@code INFO }, {@code DEBUG}, {@code TRACE}), never the
 * localized names of {@code java.util.logging} ({@code INFORMATION}, {@code WARNUNG}).
 */
public final class LogFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** Formats {@code {0}} placeholders like {@code System.Logger} does; only its formatMessage is used. */
    private static final Formatter MESSAGES = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    private LogFormat() {
    }

    /**
     * The level name, five characters wide.
     *
     * @param level a level of {@code java.util.logging} (what {@code System.Logger} maps to)
     * @return {@code ERROR}, {@code WARN }, {@code INFO }, {@code DEBUG} or {@code TRACE}
     */
    public static String level(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN ";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO ";
        }
        if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    /**
     * One console line: {@code HH:mm:ss LEVEL message}; a thrown exception adds its type and
     * message, not the stack trace.
     *
     * @param record the record
     * @return the line, not yet redacted or made printable
     */
    public static String consoleLine(LogRecord record) {
        StringBuilder line = new StringBuilder();
        line.append(TIME.format(instant(record))).append(' ').append(level(record.getLevel())).append(' ')
                .append(MESSAGES.formatMessage(record));
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            line.append(" (").append(thrown.getClass().getSimpleName());
            if (thrown.getMessage() != null) {
                line.append(": ").append(thrown.getMessage());
            }
            line.append(')');
        }
        return line.toString();
    }

    /**
     * One line of {@code logs/cloud.log}: date, time with milliseconds, level, logger name and
     * message, followed by the whole stack trace of a thrown exception.
     *
     * @param record the record
     * @return the text, ending with a line break, not yet redacted
     */
    public static String fileLine(LogRecord record) {
        StringBuilder line = new StringBuilder();
        line.append(DATE_TIME.format(instant(record))).append(' ').append(level(record.getLevel())).append(' ');
        String logger = record.getLoggerName();
        if (logger != null && !logger.isEmpty()) {
            line.append('[').append(logger.substring(logger.lastIndexOf('.') + 1)).append("] ");
        }
        line.append(MESSAGES.formatMessage(record)).append(System.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            try (PrintWriter out = new PrintWriter(trace)) {
                record.getThrown().printStackTrace(out);
            }
            line.append(trace);
        }
        return line.toString();
    }

    private static Instant instant(LogRecord record) {
        return record.getInstant() == null ? Instant.now() : record.getInstant();
    }
}
