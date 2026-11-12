// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import net.vectolyn.cloud.api.event.ApiEvent;

/**
 * Turns the node's own log records into {@code node-log} events. cloud-node adds it to the root
 * logger next to the file handler ({@link ApiServer#logHandler()}).
 *
 * <p>Only INFO and above; records of Netty and of the API itself are skipped, so a problem of the
 * stream cannot feed itself. A stack trace is never sent, only the name of the exception. The text
 * is redacted by the event hub like an output line, control characters are replaced and it is cut
 * to {@value #MAX_MESSAGE} characters. Building the event is skipped while no stream wants it.
 */
final class ApiLogHandler extends Handler {

    /** Longest message sent. */
    static final int MAX_MESSAGE = 2000;

    private final Consumer<ApiEvent> publisher;
    private final Predicate<String> wanted;
    private final SimpleFormatter formatter = new SimpleFormatter();
    private volatile boolean closed;

    ApiLogHandler(Consumer<ApiEvent> publisher, Predicate<String> wanted) {
        this.publisher = publisher;
        this.wanted = wanted;
        setLevel(Level.INFO);
    }

    @Override
    public void publish(LogRecord record) {
        if (closed || record == null || !isLoggable(record) || !wanted.test("node-log")) {
            return;
        }
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        if (logger.startsWith("io.netty") || logger.startsWith("net.vectolyn.cloud.api")) {
            return;
        }
        String message;
        try {
            message = formatter.formatMessage(record);
        } catch (RuntimeException e) {
            message = String.valueOf(record.getMessage());
        }
        if (message == null) {
            message = "";
        }
        if (record.getThrown() != null) {
            message = message + " (" + record.getThrown().getClass().getSimpleName() + ")";
        }
        message = message.replaceAll("\\p{Cntrl}", " ");
        if (message.length() > MAX_MESSAGE) {
            message = message.substring(0, MAX_MESSAGE);
        }
        publisher.accept(new ApiEvent.NodeLog(level(record.getLevel()), logger, message));
    }

    private static String level(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "error";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "warning";
        }
        return "info";
    }

    @Override
    public void flush() {
        // events are sent at once
    }

    @Override
    public void close() {
        closed = true;
    }
}
