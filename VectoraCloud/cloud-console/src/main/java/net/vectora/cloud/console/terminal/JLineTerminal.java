package net.vectora.cloud.console.terminal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp;

/**
 * Console on a real terminal with JLine: line editing, history, tab completion, colors.
 *
 * <ul>
 *   <li>History lives in a file in the cloud home (see {@link TerminalFactory}). Only
 *       {@link LineKind#COMMAND} lines that pass the history filter are stored; answers to
 *       questions and secrets never are, and lines starting with a space neither.</li>
 *   <li>Secrets are typed masked ({@code *}) on a native terminal ({@code ffm}, {@code jni}). JLine's
 *       dumb terminal cannot switch off the echo of the operating system, so there the typed
 *       secret stays visible; {@link #hidesSecrets()} says so and the question API warns.</li>
 *   <li>{@link #print} uses {@link LineReader#printAbove}, so log lines appear above the line being
 *       typed.</li>
 *   <li>History expansion ({@code !!}, {@code !prefix}) is off, so a {@code !} in a value stays as typed.</li>
 * </ul>
 */
public final class JLineTerminal implements ConsoleTerminal {

    private static final System.Logger LOG = System.getLogger(JLineTerminal.class.getName());
    private static final char MASK = '*';
    private static final int HISTORY_SIZE = 500;

    private final Terminal terminal;
    private final LineReader reader;
    private final FilteringHistory history;
    private final boolean ansi;
    private final String description;

    /**
     * Console on {@code terminal}.
     *
     * @param completer   candidates for the line up to the cursor
     * @param historyFile file for the history, or null to keep it in memory only
     * @param historyAllowed decides which command lines may be stored
     * @param description short text for the log
     */
    public JLineTerminal(Terminal terminal, Function<String, List<String>> completer, Path historyFile,
                         Predicate<String> historyAllowed, String description) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.history = new FilteringHistory(Objects.requireNonNull(historyAllowed, "historyAllowed"));
        this.description = Objects.requireNonNull(description, "description");
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("VectoraCloud")
                .history(history)
                .completer((lineReader, line, candidates) -> {
                    String before = line.line().substring(0, Math.min(line.cursor(), line.line().length()));
                    for (String value : completer.apply(before)) {
                        candidates.add(new Candidate(value, value, null, null, null, null, true));
                    }
                })
                .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_SIZE)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.INSERT_TAB, false)
                .option(LineReader.Option.AUTO_FRESH_LINE, true);
        if (historyFile != null) {
            builder.variable(LineReader.HISTORY_FILE, historyFile);
        }
        this.reader = builder.build();
        this.ansi = supportsAnsi(terminal);
    }

    /** Whether the terminal takes ANSI colors: not a dumb terminal, at least 8 colors, no {@code NO_COLOR}. */
    static boolean supportsAnsi(Terminal terminal) {
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isEmpty()) {
            return false;
        }
        String type = terminal.getType() == null ? "" : terminal.getType().toLowerCase(Locale.ROOT);
        if (type.startsWith(Terminal.TYPE_DUMB)) {
            return false;
        }
        Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        return colors != null && colors >= 8;
    }

    @Override
    public ReadResult readLine(String prompt, LineKind kind) {
        boolean command = kind == LineKind.COMMAND;
        history.suppressed = !command;
        reader.setVariable(LineReader.DISABLE_HISTORY, !command);
        try {
            String line = kind == LineKind.SECRET ? reader.readLine(prompt, MASK) : reader.readLine(prompt);
            return new ReadResult.Line(line);
        } catch (UserInterruptException e) {
            return new ReadResult.Interrupted();
        } catch (EndOfFileException e) {
            return new ReadResult.EndOfInput();
        } finally {
            reader.setVariable(LineReader.DISABLE_HISTORY, false);
            history.suppressed = false;
        }
    }

    @Override
    public void print(String text) {
        reader.printAbove(text);
    }

    @Override
    public boolean ansi() {
        return ansi;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public boolean hidesSecrets() {
        return !(terminal instanceof DumbTerminal);
    }

    @Override
    public void clearScreen() {
        if (ansi && terminal.puts(InfoCmp.Capability.clear_screen)) {
            terminal.flush();
        }
    }

    @Override
    public String description() {
        return description;
    }

    /** Lines currently in the history, oldest first (for tests and the {@code history} view). */
    public List<String> historyLines() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        history.forEach(entry -> lines.add(entry.line()));
        return lines;
    }

    @Override
    public void close() {
        try {
            history.save();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save the console history", e);
        }
        try {
            terminal.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Could not close the terminal", e);
        }
    }

    /** History that refuses lines read for questions and lines the filter rejects. */
    static final class FilteringHistory extends DefaultHistory {

        private final Predicate<String> allowed;
        volatile boolean suppressed;

        FilteringHistory(Predicate<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void add(Instant time, String line) {
            if (suppressed || line == null || !allowed.test(line)) {
                return;
            }
            super.add(time, line);
        }
    }
}
