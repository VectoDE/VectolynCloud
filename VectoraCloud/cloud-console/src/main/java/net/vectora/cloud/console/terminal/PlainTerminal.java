package net.vectora.cloud.console.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.vectora.cloud.console.command.CommandLineParser;

/**
 * Console without a terminal: Docker without {@code -t}, Pterodactyl, systemd, or a pipe. Reads
 * stdin line by line (UTF-8), writes plain lines without colors, keeps no history.
 *
 * <ul>
 *   <li>The main prompt is not printed, because the output usually goes to a log viewer where a
 *       prompt would end up in front of the next log line. Questions print their prompt as a line.</li>
 *   <li>The end of stdin is {@link ReadResult.EndOfInput}. The console then stops reading, but the
 *       cloud keeps running: under systemd or in Docker without {@code -i} stdin is empty from
 *       the start. Stop the cloud with a signal (SIGTERM, {@code docker stop}) instead.</li>
 *   <li>Lines longer than {@value CommandLineParser#MAX_LENGTH} characters are cut off there; the
 *       rest of the line is read and dropped, so the command parser reports the length.</li>
 * </ul>
 */
public final class PlainTerminal implements ConsoleTerminal {

    private final Reader in;
    private final PrintStream out;
    private final Object readLock = new Object();
    private boolean ended;

    /** Console on {@code in} and {@code out}; {@code in} is read as UTF-8. */
    public PlainTerminal(InputStream in, PrintStream out) {
        this.in = new InputStreamReader(Objects.requireNonNull(in, "in"), StandardCharsets.UTF_8);
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public ReadResult readLine(String prompt, LineKind kind) {
        if (kind != LineKind.COMMAND && prompt != null && !prompt.isEmpty()) {
            print(prompt);
        }
        synchronized (readLock) {
            if (ended) {
                return new ReadResult.EndOfInput();
            }
            try {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    if (c == '\n') {
                        return new ReadResult.Line(stripCarriageReturn(line));
                    }
                    if (line.length() <= CommandLineParser.MAX_LENGTH) {
                        line.append((char) c);
                    }
                }
                ended = true;
                return line.isEmpty() ? new ReadResult.EndOfInput() : new ReadResult.Line(stripCarriageReturn(line));
            } catch (IOException e) {
                ended = true;
                return new ReadResult.EndOfInput();
            }
        }
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    @Override
    public void print(String text) {
        synchronized (out) {
            out.println(text);
            out.flush();
        }
    }

    @Override
    public boolean ansi() {
        return false;
    }

    @Override
    public boolean interactive() {
        return false;
    }

    @Override
    public void clearScreen() {
        // a log viewer cannot be cleared
    }

    @Override
    public String description() {
        return "plain (no terminal, line by line from stdin)";
    }

    @Override
    public void close() {
        out.flush();
    }
}
