package net.vectora.cloud.console;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import net.vectora.cloud.console.terminal.ConsoleTerminal;

/**
 * Prints {@code java.util.logging} records above the prompt, so log output keeps running while a
 * command is being typed. {@code System.Logger} goes to {@code java.util.logging} by default, so
 * installing this handler on the root logger covers the whole cloud.
 *
 * <p>Format: {@code HH:mm:ss LEVEL message}, warnings and errors colored on an ANSI terminal. A
 * thrown exception adds its type and message, not the stack trace (the log file keeps that). Every
 * line runs through the redactor first.
 */
public final class ConsoleLogHandler extends Handler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ConsoleTerminal terminal;
    private final UnaryOperator<String> redactor;
    private final SimpleFormatter messages = new SimpleFormatter();

    ConsoleLogHandler(ConsoleTerminal terminal, UnaryOperator<String> redactor) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append(TIME.format(record.getInstant() == null ? Instant.now() : record.getInstant()))
                .append(' ').append(record.getLevel().getName()).append(' ')
                .append(messages.formatMessage(record));
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            line.append(" (").append(thrown.getClass().getSimpleName());
            if (thrown.getMessage() != null) {
                line.append(": ").append(thrown.getMessage());
            }
            line.append(')');
        }
        String text = redactor.apply(line.toString());
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
