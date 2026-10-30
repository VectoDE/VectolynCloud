package net.vectolyn.cloud.node.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import net.vectolyn.cloud.config.secret.RedactingFilter;

/**
 * Reads the output of a service process line by line, removes secrets, appends each line to the
 * service's log file and hands it to a consumer.
 *
 * <p>Log files live in {@code <home>/logs/services/}: {@code <id>.log} for the current start,
 * {@code <id>.1.log} to {@code <id>.<KEEP>.log} for the starts before (renamed on each start, the
 * oldest is dropped). Lines are decoded as UTF-8 (broken sequences become U+FFFD) and cut at
 * {@value #MAX_LINE_BYTES} bytes, so a process that never prints a line break cannot fill the
 * memory of the node. Every line goes through the {@link RedactingFilter} before it is written or
 * passed on.
 */
public final class ServiceOutput implements Runnable {

    /** Folder of the service logs, relative to the home. */
    public static final String LOG_DIR = "logs/services";
    /** Older log files kept per service. */
    public static final int KEEP = 5;
    /** Longest line in bytes; longer output is split. */
    public static final int MAX_LINE_BYTES = 8192;

    private final InputStream in;
    private final Writer log;
    private final RedactingFilter filter;
    private final Consumer<String> lines;

    /**
     * Creates a pump.
     *
     * @param in     the process output (standard output with standard error merged)
     * @param log    the log file writer; closed when the stream ends
     * @param filter removes secrets
     * @param lines  receives every filtered line
     */
    public ServiceOutput(InputStream in, Writer log, RedactingFilter filter, Consumer<String> lines) {
        this.in = in;
        this.log = log;
        this.filter = filter;
        this.lines = lines;
    }

    /**
     * Rotates the logs of a service and opens a new {@code <id>.log}.
     *
     * @param logDir  {@code <home>/logs/services}
     * @param service service id
     * @return a writer for the new log file
     * @throws IOException if the folder or file cannot be created
     */
    public static Writer openLog(Path logDir, String service) throws IOException {
        Files.createDirectories(logDir);
        for (int i = KEEP; i >= 1; i--) {
            Path from = i == 1 ? logDir.resolve(service + ".log") : logDir.resolve(service + "." + (i - 1) + ".log");
            Path to = logDir.resolve(service + "." + i + ".log");
            if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path current = logDir.resolve(service + ".log");
        Files.deleteIfExists(current);
        return Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    @Override
    public void run() {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[8192];
        try (Writer out = log) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        emit(line, decoder, out);
                    } else {
                        line.write(b);
                        if (line.size() >= MAX_LINE_BYTES) {
                            emit(line, decoder, out);
                        }
                    }
                }
            }
            if (line.size() > 0) {
                emit(line, decoder, out);
            }
        } catch (IOException e) {
            // The process ended or its pipe was closed; nothing more to read.
        }
    }

    private void emit(ByteArrayOutputStream bytes, CharsetDecoder decoder, Writer out) throws IOException {
        String text;
        try {
            text = decoder.reset().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        bytes.reset();
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        String clean = filter.redact(text);
        try {
            out.write(clean);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            // A full disk must not stop the pump; the process would block on a full pipe otherwise.
        }
        lines.accept(clean);
    }
}
