package net.vectora.cloud.monitor.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.vectora.cloud.monitor.Fakes;
import net.vectora.cloud.monitor.alert.Alert;
import net.vectora.cloud.monitor.alert.AlertEvent;
import net.vectora.cloud.monitor.alert.RuleParser;
import net.vectora.cloud.monitor.alert.Severity;
import org.junit.jupiter.api.Test;

class NotificationDispatcherTest {

    private static Alert alert(int id, Severity severity) {
        return new Alert(id, "rule", "lobby-1", severity, RuleParser.parse("tps < 18"), 5, Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static final class Recording implements Notifier {
        final List<AlertEvent> sent = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public void send(AlertEvent event) {
            sent.add(event);
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void filtersBySeverityResolvedAndAcknowledged() {
        Recording notifier = new Recording();
        try (NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(notifier), Severity.CRITICAL, false,
                s -> s)) {
            dispatcher.onAlert(new AlertEvent.Fired(alert(1, Severity.WARNING)));
            dispatcher.onAlert(new AlertEvent.Fired(alert(2, Severity.CRITICAL)));
            dispatcher.onAlert(new AlertEvent.Resolved(alert(2, Severity.CRITICAL)));
            dispatcher.onAlert(new AlertEvent.Acknowledged(alert(2, Severity.CRITICAL)));
        }
        assertEquals(1, notifier.sent.size(), "close sends what is queued first");
        assertEquals(2, notifier.sent.get(0).alert().id());
        assertTrue(notifier.closed);
    }

    @Test
    void resolvedEventsAreSentWhenWanted() {
        Recording notifier = new Recording();
        try (NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(notifier), Severity.WARNING, true,
                s -> s)) {
            dispatcher.onAlert(new AlertEvent.Fired(alert(1, Severity.WARNING)));
            dispatcher.onAlert(new AlertEvent.Resolved(alert(1, Severity.WARNING)));
        }
        assertEquals(2, notifier.sent.size());
    }

    @Test
    void failuresAreLoggedThroughTheRedactorAndCounted() {
        Notifier failing = new Notifier() {
            @Override
            public void send(AlertEvent event) throws IOException {
                throw new IOException("webhook: answered with HTTP 404 for SECRET");
            }

            @Override
            public String name() {
                return "webhook";
            }
        };
        NotificationDispatcher dispatcher;
        try (Fakes.LogCapture log = new Fakes.LogCapture(NotificationDispatcher.class.getName())) {
            dispatcher = new NotificationDispatcher(List.of(failing), Severity.WARNING, true,
                    s -> s.replace("SECRET", "***"));
            dispatcher.onAlert(new AlertEvent.Fired(alert(7, Severity.WARNING)));
            dispatcher.close();
            assertEquals(1, dispatcher.failed());
            assertTrue(log.text().contains("Alert #7 could not be sent with webhook: webhook: answered with HTTP 404 for ***"),
                    log.text());
            assertFalse(log.text().contains("SECRET"));
        }
    }

    @Test
    void aFullQueueDropsInsteadOfBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Notifier stuck = new Notifier() {
            @Override
            public void send(AlertEvent event) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String name() {
                return "stuck";
            }
        };
        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(stuck), Severity.WARNING, true, s -> s);
        try {
            dispatcher.onAlert(new AlertEvent.Fired(alert(0, Severity.WARNING)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long before = System.nanoTime();
            for (int i = 1; i <= NotificationDispatcher.QUEUE_SIZE + 25; i++) {
                dispatcher.onAlert(new AlertEvent.Fired(alert(i, Severity.WARNING)));
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000, "onAlert never blocks");
            assertEquals(25, dispatcher.dropped());
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }
}
