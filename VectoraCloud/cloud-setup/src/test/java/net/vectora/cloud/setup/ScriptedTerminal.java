package net.vectora.cloud.setup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.vectora.cloud.console.terminal.ConsoleTerminal;
import net.vectora.cloud.console.terminal.ReadResult;

/** Scripted terminal: hands out typed lines, runs actions between them, records what was printed and read. */
final class ScriptedTerminal implements ConsoleTerminal {

    /** One read: the prompt and how it was read. */
    record Read(String prompt, LineKind kind) {
    }

    private final Deque<Object> input = new ArrayDeque<>();
    private final List<String> printed = new ArrayList<>();
    private final List<Read> reads = new ArrayList<>();
    private boolean hidesSecrets = true;

    /** Queues typed lines. */
    ScriptedTerminal type(String... lines) {
        for (String line : lines) {
            input.add(new ReadResult.Line(line));
        }
        return this;
    }

    /** Queues {@code count} presses of Enter. */
    ScriptedTerminal enter(int count) {
        for (int i = 0; i < count; i++) {
            input.add(new ReadResult.Line(""));
        }
        return this;
    }

    /** Runs {@code action} right before the next queued line is handed out (the operator edits a file). */
    ScriptedTerminal then(Runnable action) {
        input.add(action);
        return this;
    }

    /** Queues Ctrl+C. */
    ScriptedTerminal interrupt() {
        input.add(new ReadResult.Interrupted());
        return this;
    }

    ScriptedTerminal hidingSecrets(boolean value) {
        hidesSecrets = value;
        return this;
    }

    @Override
    public ReadResult readLine(String prompt, LineKind kind) {
        reads.add(new Read(prompt, kind));
        while (input.peek() instanceof Runnable action) {
            input.poll();
            action.run();
        }
        Object next = input.poll();
        return next == null ? new ReadResult.EndOfInput() : (ReadResult) next;
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }

    @Override
    public boolean ansi() {
        return false;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public boolean hidesSecrets() {
        return hidesSecrets;
    }

    @Override
    public void clearScreen() {
    }

    @Override
    public String description() {
        return "scripted";
    }

    @Override
    public void close() {
    }

    String output() {
        return String.join("\n", printed);
    }

    List<Read> reads() {
        return reads;
    }

    int pending() {
        return input.size();
    }
}
