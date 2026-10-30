package net.vectolyn.cloud.console;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.vectolyn.cloud.console.terminal.ConsoleTerminal;

/**
 * Prints {@code java.util.logging} records above the prompt, so log output keeps running while a
 * command is being typed. {@code System.Logger} goes to {@code java.util.logging} by default, so
 * installing this handler on the root logger covers the whole cloud.
 *
 * <p>Format: {@link LogFormat#consoleLine} ({@code HH:mm:ss LEVEL message}), warnings and errors
 * colored on an ANSI terminal. A thrown exception adds its type and message, not the stack trace
 * ({@code logs/cloud.log} keeps that). Every line runs through the redactor first, then control
 * characters other than line breaks and tabs become {@code ?}, so a logged file name or message
 * cannot send escape sequences to the terminal.
 */
public final class ConsoleLogHandler extends Handler {

    private final ConsoleTerminal terminal;
    private final UnaryOperator<String> redactor;

    ConsoleLogHandler(ConsoleTerminal terminal, UnaryOperator<String> redactor) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        String text = TerminalOutput.printable(redactor.apply(LogFormat.consoleLine(record)));
        Style style = record.getLevel().intValue() >= Level.SEVERE.intValue() ? Style.ERROR
                : record.getLevel().intValue() >= Level.WARNING.intValue() ? Style.WARNING : Style.PLAIN;
        terminal.print(style.apply(text, terminal.ansi()));
    }

    @Override
    public void flush() {
        // print() flushes every line
    }

    @Override
    public void close() {
        // the console owns the terminal
    }
}
