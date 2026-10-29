package net.vectora.cloud.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.ReadResult;

/** Scripted terminal for tests: hands out prepared input and records everything printed and asked. */
public final class FakeTerminal implements ConsoleTerminal {

    /** One read: what was asked and how. */
    public record Read(String prompt, LineKind kind) {
    }

    private final Deque<ReadResult> input = new ArrayDeque<>();
    private final List<String> printed = new CopyOnWriteArrayList<>();
    private final List<Read> reads = new ArrayList<>();
    private final boolean ansi;
    private final boolean interactive;
    private boolean hidesSecrets = true;
    private int clears;
    private boolean closed;

    public FakeTerminal() {
        this(false, true);
    }

    public FakeTerminal(boolean ansi, boolean interactive) {
        this.ansi = ansi;
        this.interactive = interactive;
    }

    /** Queues typed lines. */
    public FakeTerminal type(String... lines) {
        for (String line : lines) {
            input.add(new ReadResult.Line(line));
        }
        return this;
    }

    /** Queues Ctrl+C. */
    public FakeTerminal interrupt() {
        input.add(new ReadResult.Interrupted());
        return this;
    }

    /** Queues Ctrl+D / end of input. */
    public FakeTerminal endOfInput() {
        input.add(new ReadResult.EndOfInput());
        return this;
    }

    @Override
    public ReadResult readLine(String prompt, LineKind kind) {
        reads.add(new Read(prompt, kind));
        ReadResult next = input.poll();
        return next == null ? new ReadResult.EndOfInput() : next;
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }

    @Override
    public boolean ansi() {
        return ansi;
    }

    @Override
    public boolean interactive() {
        return interactive;
    }

    @Override
    public boolean hidesSecrets() {
        return hidesSecrets;
    }

    /** Whether the fake claims to hide secrets (default true, as a native terminal). */
    public FakeTerminal hidingSecrets(boolean value) {
        hidesSecrets = value;
        return this;
    }

    @Override
    public void clearScreen() {
        clears++;
    }

    @Override
    public String description() {
        return "fake";
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Everything printed, one entry per print call. */
    public List<String> printed() {
        return printed;
    }

    /** Everything printed, joined with line breaks. */
    public String output() {
        return String.join("\n", printed);
    }

    /** Forgets what was printed so far. */
    public void clearOutput() {
        printed.clear();
    }

    /** Every read so far. */
    public List<Read> reads() {
        return reads;
    }

    public int clears() {
        return clears;
    }

    public boolean closed() {
        return closed;
    }

    /** Input left over. */
    public int pending() {
        return input.size();
    }
}
