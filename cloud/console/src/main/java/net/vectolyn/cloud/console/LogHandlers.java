package net.vectolyn.cloud.console;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.vectolyn.cloud.config.io.RegularPaths;

/**
 * The two log handlers besides the console's own ({@link ConsoleLogHandler}), both in
 * {@link LogFormat}: one on standard output for the time before the console is up (and for a node
 * without console), and one for {@code logs/cloud.log}.
 */
public final class LogHandlers {

    /** File name of the cloud log below {@code logs/}. */
    public static final String CLOUD_LOG = "cloud.log";

    /** Older cloud logs kept: {@code cloud.1.log} to {@code cloud.<KEEP>.log}, one per start. */
    public static final int KEEP = 5;

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
     * Rotates the cloud log ({@code cloud.log} becomes {@code cloud.1.log} and so on, the oldest is
     * dropped) and opens a new one in UTF-8.
     *
     * @param logs     {@code <home>/logs}
     * @param redactor removes secrets from every line
     * @return the handler
     * @throws IOException if the folder is a link or the file cannot be created
     */
    public static FileHandler file(Path logs, UnaryOperator<String> redactor) throws IOException {
        RegularPaths.requireDirectoryOrMissing(logs);
        Files.createDirectories(logs);
        for (int i = KEEP; i >= 1; i--) {
            Path from = logs.resolve(i == 1 ? CLOUD_LOG : "cloud." + (i - 1) + ".log");
            Path to = logs.resolve("cloud." + i + ".log");
            if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path current = logs.resolve(CLOUD_LOG);
        Files.deleteIfExists(current);
        Writer writer = Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        return new FileHandler(writer, redactor);
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
