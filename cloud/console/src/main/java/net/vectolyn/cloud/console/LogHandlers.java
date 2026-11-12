// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.console;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.vectolyn.cloud.config.model.LogsConfig;

/**
 * The two log handlers besides the console's own ({@link ConsoleLogHandler}), both in
 * {@link LogFormat}: one on standard output for the time before the console is up (and for a node
 * without console), and one for {@code logs/cloud.log} ({@link RotatingLogFile}).
 */
public final class LogHandlers {

    /** File name of the cloud log below {@code logs/}. */
    public static final String CLOUD_LOG = "cloud.log";

    /** Base name of the cloud log for {@link RotatingLogFile}. */
    public static final String CLOUD_LOG_BASE = "cloud";

    private LogHandlers() {
    }

    /**
     * A handler that prints {@link LogFormat#consoleLine} lines through a {@link PrintStream}. Going
     * through the stream (not its bytes) keeps the encoding of the console: Java encodes
     * {@code System.out} in {@code stdout.encoding}, the code page of a Windows console, so umlauts
     * come out right, which {@link java.util.logging.ConsoleHandler} (UTF-8 bytes) breaks.
     *
     * @param out the stream, normally {@code System.out}
     * @return the handler
     */
    public static StreamHandler stream(PrintStream out) {
        return new StreamHandler(out);
    }

    /**
     * Opens {@code cloud.log} with the default retention ({@link LogsConfig#defaults()}) and the
     * system clock; see {@link #file(Path, UnaryOperator, Supplier, Clock)}.
     *
     * @param logs     {@code <home>/logs}
     * @param redactor removes secrets from every line
     * @return the handler
     * @throws IOException if the folder is a link or the file cannot be created
     */
    public static FileHandler file(Path logs, UnaryOperator<String> redactor) throws IOException {
        return file(logs, redactor, LogsConfig::defaults, Clock.system(ZoneId.systemDefault()));
    }

    /**
     * Opens {@code logs/cloud.log} in UTF-8 as a {@link RotatingLogFile}: the file of the last run
     * becomes {@code cloud-<date>.log}, a new file begins at midnight and at {@code max-file-size},
     * and files older than {@code retention-days} are deleted.
     *
     * @param logs     {@code <home>/logs}
     * @param redactor removes secrets from every line
     * @param settings {@code logs:} of {@code cloud.yml}, read at each rotation
     * @param clock    time and time zone of the file dates
     * @return the handler
     * @throws IOException if the folder is a link or the file cannot be created
     */
    public static FileHandler file(Path logs, UnaryOperator<String> redactor, Supplier<LogsConfig> settings,
                                   Clock clock) throws IOException {
        return new FileHandler(RotatingLogFile.open(logs, CLOUD_LOG_BASE, settings, clock), redactor);
    }

    /** See {@link #stream(PrintStream)}. */
    public static final class StreamHandler extends Handler {

        private final PrintStream out;
        private volatile UnaryOperator<String> redactor = UnaryOperator.identity();

        private StreamHandler(PrintStream out) {
            this.out = Objects.requireNonNull(out, "out");
        }

        /**
         * Sets what removes secrets from each line, once the secrets are known.
         *
         * @param value the redactor
         */
        public void redactor(UnaryOperator<String> value) {
            this.redactor = Objects.requireNonNull(value, "value");
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }
            String line = TerminalOutput.printable(redactor.apply(LogFormat.consoleLine(record)));
            synchronized (out) {
                out.println(line);
                out.flush();
            }
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void close() {
            out.flush();
        }
    }

    /** See {@link #file(Path, UnaryOperator)}. */
    public static final class FileHandler extends Handler {

        private final Writer writer;
        private final UnaryOperator<String> redactor;
        private boolean closed;

        private FileHandler(Writer writer, UnaryOperator<String> redactor) {
            this.writer = writer;
            this.redactor = Objects.requireNonNull(redactor, "redactor");
        }

        @Override
        public synchronized void publish(LogRecord record) {
            if (closed || record == null || !isLoggable(record)) {
                return;
            }
            try {
                writer.write(redactor.apply(LogFormat.fileLine(record)));
                writer.flush();
            } catch (IOException | RuntimeException e) {
                reportError("cannot write logs/cloud.log", e, ErrorManager.WRITE_FAILURE);
            }
        }

        @Override
        public synchronized void flush() {
            try {
                writer.flush();
            } catch (IOException e) {
                reportError("cannot flush logs/cloud.log", e, ErrorManager.FLUSH_FAILURE);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.close();
            } catch (IOException e) {
                reportError("cannot close logs/cloud.log", e, ErrorManager.CLOSE_FAILURE);
            }
        }
    }
}
