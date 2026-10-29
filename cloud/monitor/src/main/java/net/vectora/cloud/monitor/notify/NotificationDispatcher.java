package net.vectora.cloud.monitor.notify;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectora.cloud.monitor.alert.AlertEvent;
import net.vectora.cloud.monitor.alert.AlertListener;
import net.vectora.cloud.monitor.alert.Severity;

/**
 * Hands alert events to the notifiers on a thread of its own, so a slow or unreachable webhook
 * never holds up sampling. The queue holds {@value #QUEUE_SIZE} events; when it is full, new
 * events are dropped and counted ({@link #dropped()}), and the log says so once per burst.
 *
 * <p>Filters: acknowledgements are not sent, alerts below {@code minSeverity} are not sent, and
 * resolutions only if {@code sendResolved}.
 */
public final class NotificationDispatcher implements AlertListener, AutoCloseable {

    /** Events waiting at most. */
    public static final int QUEUE_SIZE = 100;

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class.getName());
    private static final Object STOP = new Object();

    private final List<Notifier> notifiers;
    private final Severity minSeverity;
    private final boolean sendResolved;
    private final UnaryOperator<String> redactor;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SIZE + 1);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Thread worker;
    private volatile boolean closed;
    private volatile boolean dropLogged;

    /**
     * Creates and starts the dispatcher.
     *
     * @param notifiers    where events go
     * @param minSeverity  lowest severity that is sent
     * @param sendResolved whether resolutions are sent
     * @param redactor     applied to every log line it writes (pass {@code RedactingFilter::redact})
     */
    public NotificationDispatcher(List<Notifier> notifiers, Severity minSeverity, boolean sendResolved,
                                  UnaryOperator<String> redactor) {
        this.notifiers = List.copyOf(notifiers);
        this.minSeverity = Objects.requireNonNull(minSeverity, "minSeverity");
        this.sendResolved = sendResolved;
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.worker = Thread.ofPlatform().daemon().name("vectora-monitor-notify").unstarted(this::run);
        worker.start();
    }

    @Override
    public void onAlert(AlertEvent event) {
        if (closed || !wanted(event)) {
            return;
        }
        if (queue.size() < QUEUE_SIZE && queue.offer(event)) {
            dropLogged = false;
        } else {
            dropped.incrementAndGet();
            if (!dropLogged) {
                dropLogged = true;
                LOG.warning("Notification queue is full; alerts are dropped until the notifiers catch up.");
            }
        }
    }

    private boolean wanted(AlertEvent event) {
        boolean severe = event.alert().severity().compareTo(minSeverity) >= 0;
        return switch (event.kind()) {
            case ACKNOWLEDGED -> false;
            case RESOLVED -> sendResolved && severe;
            case FIRED -> severe;
        };
    }

    private void run() {
        while (true) {
            Object next;
            try {
                next = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (!(next instanceof AlertEvent event)) {
                return;
            }
            for (Notifier notifier : notifiers) {
                try {
                    notifier.send(event);
                } catch (IOException | RuntimeException e) {
                    failed.incrementAndGet();
                    String message = e instanceof IOException ? e.getMessage() : e.getClass().getSimpleName();
                    LOG.log(Level.WARNING, redactor.apply("Alert #" + event.alert().id() + " could not be sent with "
                            + notifier.name() + ": " + message));
                }
            }
        }
    }

    /** Events dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Deliveries that failed. */
    public long failed() {
        return failed.get();
    }

    /**
     * Sends what is queued (waiting at most 15 seconds), then stops the thread and closes the notifiers.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!queue.offer(STOP, 1, TimeUnit.SECONDS)) {
                worker.interrupt();
            }
            worker.join(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.interrupt();
        for (Notifier notifier : notifiers) {
            try {
                notifier.close();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Notifier did not close cleanly", e);
            }
        }
    }
}
