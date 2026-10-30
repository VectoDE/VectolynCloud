package net.vectolyn.cloud.bridge;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.vectolyn.protocol.message.Message;

/**
 * The only way a bridge talks to the cloud. {@code CloudClient} writes to a blocking socket, and a
 * slow or stuck cloud must never hold up a server thread (the Paper main thread, a Velocity event
 * thread). So every call goes into a bounded queue and one daemon thread of the bridge,
 * {@value #THREAD_NAME}, makes the actual call.
 *
 * <ul>
 *   <li>Submitting never blocks. When the queue is full the action is dropped and counted
 *       ({@link #dropped()}); the cloud then sees the next status report and, after a reconnect,
 *       the repeated ready signal.</li>
 *   <li>Status reports are coalesced: however many arrive while one is waiting, only the newest is
 *       sent and only one queue slot is used.</li>
 *   <li>{@link #close(Duration)} sends what is queued, then closes the link, and waits at most the
 *       given time. That bounded wait at plugin shutdown is the one place a server thread waits.
 *       If the sender thread still hangs in a call after that, the link is closed from the closing
 *       thread, so a stuck socket is closed all the same.</li>
 * </ul>
 */
public final class BridgeSender implements AutoCloseable {

    /** Name of the sender thread. */
    public static final String THREAD_NAME = "vectolyn-bridge-sender";

    /** Default queue capacity: far more than a proxy produces between two sends. */
    public static final int DEFAULT_CAPACITY = 1024;

    /** Default wait in {@link #close()}. {@code CloudClient.close()} itself returns within about a second. */
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(3);

    private static final System.Logger LOG = System.getLogger(BridgeSender.class.getName());

    private final CloudLink link;
    private final BlockingQueue<Consumer<CloudLink>> queue;
    private final AtomicReference<StatusSnapshot> pendingStatus = new AtomicReference<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Consumer<CloudLink> sendStatus = this::sendPendingStatus;
    private final Consumer<CloudLink> closeLink = CloudLink::close;
    private final Object lifecycle = new Object();
    private Thread worker;
    private volatile boolean closed;

    private BridgeSender(CloudLink link, int capacity) {
        this.link = Objects.requireNonNull(link, "link");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Starts a sender for {@code link} with {@link #DEFAULT_CAPACITY}. */
    public static BridgeSender start(CloudLink link) {
        return start(link, DEFAULT_CAPACITY);
    }

    /** Starts a sender for {@code link} whose queue holds {@code capacity} actions. */
    public static BridgeSender start(CloudLink link, int capacity) {
        BridgeSender sender = new BridgeSender(link, capacity);
        Thread thread = new Thread(sender::run, THREAD_NAME);
        thread.setDaemon(true);
        synchronized (sender.lifecycle) {
            sender.worker = thread;
        }
        thread.start();
        return sender;
    }

    /** Queues {@link CloudLink#start()}. */
    public boolean startLink() {
        return submit(CloudLink::start);
    }

    /** Queues {@link CloudLink#markReady()}. */
    public boolean markReady() {
        return submit(CloudLink::markReady);
    }

    /** Queues a player event. */
    public boolean send(Message playerEvent) {
        Objects.requireNonNull(playerEvent, "playerEvent");
        return submit(target -> target.send(playerEvent));
    }

    /**
     * Queues a status report. If a report is already waiting, it is replaced by this one.
     *
     * @return {@code false} only if the sender is closed or the queue was full
     */
    public boolean reportStatus(StatusSnapshot status) {
        Objects.requireNonNull(status, "status");
        if (closed) {
            return false;
        }
        if (pendingStatus.getAndSet(status) != null) {
            return true;
        }
        if (!submit(sendStatus)) {
            // Clear whatever is pending now, also a newer report that arrived meanwhile: no drain is
            // queued for it, and a set slot would stop every later report from queuing one.
            pendingStatus.set(null);
            return false;
        }
        return true;
    }

    /**
     * Queues an action on the link. Never blocks.
     *
     * @return whether it was queued; {@code false} if the sender is closed or the queue is full
     */
    public boolean submit(Consumer<CloudLink> action) {
        Objects.requireNonNull(action, "action");
        if (closed) {
            return false;
        }
        if (!queue.offer(action)) {
            long count = dropped.incrementAndGet();
            // The first drop is worth a warning, a stuck cloud is not worth one per event.
            if (count == 1 || count % 1000 == 0) {
                LOG.log(Level.WARNING, "VectolynCloud send queue is full, {0} message(s) dropped so far", count);
            }
            return false;
        }
        return true;
    }

    /** Actions dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Whether {@link #close} was called. */
    public boolean isClosed() {
        return closed;
    }

    /** {@link #close(Duration)} with {@link #DEFAULT_CLOSE_TIMEOUT}. */
    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    /**
     * Stops accepting actions, lets the sender thread send what is queued and close the link, and
     * waits at most {@code timeout} for that. If the queue stays full for half of {@code timeout},
     * the queued actions are dropped so the link still gets closed. Safe to call more than once.
     */
    public void close(Duration timeout) {
        Thread thread;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            thread = worker;
        }
        long deadline = System.nanoTime() + Math.max(1, timeout.toNanos());
        try {
            // Half the time for queued actions to make room; after that they are given up.
            if (!queue.offer(closeLink, Math.max(1, timeout.toNanos() / 2), TimeUnit.NANOSECONDS)) {
                queue.clear();
                if (!queue.offer(closeLink)) {
                    // A submit that raced the close refilled the queue: close the link from here.
                    thread.interrupt();
                    link.close();
                    return;
                }
            }
            long left = deadline - System.nanoTime();
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOG.log(Level.WARNING, "VectolynCloud sender did not finish within {0} ms", timeout.toMillis());
            thread.interrupt();
            // The sender hangs in a call on the link, so the queued close would never run. Closing
            // the link from here closes its socket, which fails the hung write and ends the thread;
            // CloudClient.close() itself returns within about a second and may be called twice.
            link.close();
        }
    }

    private void run() {
        while (true) {
            Consumer<CloudLink> action;
            try {
                action = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                action.accept(link);
            } catch (RuntimeException | LinkageError e) {
                // Keep the sender alive: a dead sender would silently drop everything after it.
                LOG.log(Level.WARNING, "VectolynCloud call failed", e);
            }
            if (action == closeLink) {
                return;
            }
        }
    }

    private void sendPendingStatus(CloudLink target) {
        StatusSnapshot status = pendingStatus.getAndSet(null);
        if (status != null) {
            target.reportStatus(status);
        }
    }
}
