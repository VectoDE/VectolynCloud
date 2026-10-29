package net.vectora.cloud.bridge.forwarding;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Second gate behind the handshake check. Every handshake the check accepts leaves a ticket for
 * its UUID; the login itself is only allowed if it finds one. That also covers a handshake that
 * reached the server before the bridge was enabled, or one that another plugin handled.
 *
 * <p>A ticket is good for one login within {@link #TTL_NANOS}. At most {@value #MAX_TICKETS}
 * are kept; only handshakes with the right token create them, so the limit only matters if the
 * legacy proxy itself floods the backend.
 *
 * <p>Thread-safe: handshakes run on network threads, logins on the server thread.
 */
public final class AdmissionTickets {

    /** How long a ticket is valid: one minute, far more than a login takes. */
    public static final long TTL_NANOS = 60_000_000_000L;

    /** Most tickets kept; the oldest are dropped first. */
    public static final int MAX_TICKETS = 4096;

    private final LongSupplier clock;
    private final Map<UUID, Long> tickets = new LinkedHashMap<>();

    /** Tickets timed with {@link System#nanoTime()}. */
    public AdmissionTickets() {
        this(System::nanoTime);
    }

    /** Tickets with a clock of their own, for tests. */
    public AdmissionTickets(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Leaves a ticket for {@code uuid}, replacing an older one. */
    public synchronized void issue(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        long now = clock.getAsLong();
        expire(now);
        tickets.remove(uuid);
        tickets.put(uuid, now);
        while (tickets.size() > MAX_TICKETS) {
            Iterator<UUID> oldest = tickets.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Takes the ticket of {@code uuid}; {@code true} if there was a valid one. */
    public synchronized boolean consume(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        long now = clock.getAsLong();
        expire(now);
        return tickets.remove(uuid) != null;
    }

    /** Tickets currently held. */
    public synchronized int size() {
        return tickets.size();
    }

    private void expire(long now) {
        Iterator<Long> issued = tickets.values().iterator();
        while (issued.hasNext()) {
            if (now - issued.next() < TTL_NANOS) {
                return; // insertion order: everything after this one is newer
            }
            issued.remove();
        }
    }
}
