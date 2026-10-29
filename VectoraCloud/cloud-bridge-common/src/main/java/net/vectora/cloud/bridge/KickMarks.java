package net.vectora.cloud.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The network kicks ({@code NetworkKick}) the proxy heard of, and the kicks that wait for one.
 *
 * <p>The backend's mark and its kick travel on two paths: the mark over the cloud, the kick over
 * the player's connection. Either may arrive first.
 *
 * <ul>
 *   <li>A mark is kept for {@link #WINDOW}, so a kick that arrives after it is treated as a
 *       punishment ({@link #find}).</li>
 *   <li>A kick that may be a {@code /kick} waits up to {@link #WAIT} for its mark
 *       ({@link #await}); a mark that comes in the meantime completes the wait at once.</li>
 *   <li>A mark is only accepted for the server the player is on or was kicked from within
 *       {@link #WINDOW} ({@link #kickedFrom}, {@link #mark}), so a backend cannot take a
 *       player off the network who was never on it.</li>
 * </ul>
 * Thread-safe. No platform type.
 */
public final class KickMarks {

    /** How long a mark and the server of a kick are kept. */
    public static final Duration WINDOW = Duration.ofSeconds(10);

    /** How long a kick that may be a {@code /kick} waits for its mark. */
    public static final Duration WAIT = Duration.ofMillis(300);

    private final Clock clock;
    private final Map<UUID, Timed> marks = new HashMap<>();
    private final Map<UUID, Timed> kicks = new HashMap<>();
    private final Map<UUID, CompletableFuture<Optional<String>>> waiting = new HashMap<>();

    /** A text with the time it stops counting. */
    private record Timed(String text, Instant until) {
    }

    /** Creates the store with the system clock. */
    public KickMarks() {
        this(Clock.systemUTC());
    }

    /**
     * Creates the store.
     *
     * @param clock for the window
     */
    public KickMarks(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A backend kicked the player; a mark from that server is accepted for {@link #WINDOW}.
     *
     * @param player the player
     * @param server the backend
     */
    public synchronized void kickedFrom(UUID player, String server) {
        kicks.put(player, new Timed(server, clock.instant().plus(WINDOW)));
    }

    /**
     * Whether a mark from {@code server} concerns the player: they are on it or it kicked them
     * within {@link #WINDOW}.
     *
     * @param player  the player
     * @param server  the backend that sent the mark
     * @param current the backend the player is on, empty if none
     * @return whether the mark may be accepted
     */
    synchronized boolean concerns(UUID player, String server, Optional<String> current) {
        if (current.isPresent() && current.get().equals(server)) {
            return true;
        }
        Timed kick = live(kicks, player);
        return kick != null && kick.text().equals(server);
    }

    /** What became of a mark. */
    public enum Outcome {
        /**
         * The mark does not concern the player (they are not on that server and it did not kick
         * them within {@link KickMarks#WINDOW}); nothing was recorded.
         */
        IGNORED,
        /** A waiting kick of the player took the mark over and disconnects them. */
        TAKEN,
        /** The mark was recorded and nobody waited: the caller disconnects the player. */
        MARKED
    }

    /**
     * Records a mark if it concerns the player, in one step: the check and the record cannot be
     * split by another thread, so no mark is left behind for a server the player was never on.
     * If a kick of the player is waiting for one, it gets the reason now.
     *
     * @param player  the player
     * @param server  the backend that sent the mark
     * @param current the backend the player is on, empty if none
     * @param reason  the reason of the mark, possibly empty
     * @return what became of the mark
     */
    public Outcome mark(UUID player, String server, Optional<String> current, String reason) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(reason, "reason");
        CompletableFuture<Optional<String>> waiter;
        synchronized (this) {
            if (!concerns(player, server, current)) {
                return Outcome.IGNORED;
            }
            marks.put(player, new Timed(reason, clock.instant().plus(WINDOW)));
            waiter = waiting.remove(player);
        }
        // Completed outside the lock: the waiting kick goes on in this thread.
        return waiter != null && waiter.complete(Optional.of(reason)) ? Outcome.TAKEN : Outcome.MARKED;
    }

    /**
     * The reason of a mark of the player within {@link #WINDOW}.
     *
     * @param player the player
     * @return the reason, empty without a mark
     */
    public synchronized Optional<String> find(UUID player) {
        Timed mark = live(marks, player);
        return mark == null ? Optional.empty() : Optional.of(mark.text());
    }

    /**
     * Waits for a mark of the player: completes with its reason as soon as one is recorded (at
     * once if there is one), with an empty value after {@code wait}.
     *
     * @param player the player
     * @param wait   the longest wait
     * @return the reason, or empty
     */
    public CompletableFuture<Optional<String>> await(UUID player, Duration wait) {
        CompletableFuture<Optional<String>> waiter;
        CompletableFuture<Optional<String>> before;
        synchronized (this) {
            Optional<String> known = find(player);
            if (known.isPresent()) {
                return CompletableFuture.completedFuture(known);
            }
            waiter = new CompletableFuture<>();
            before = waiting.put(player, waiter);
        }
        // Completed outside the lock, as in mark and forget: the earlier wait goes on in this thread.
        if (before != null) {
            before.complete(Optional.empty());
        }
        waiter.whenComplete((result, error) -> {
            synchronized (this) {
                waiting.remove(player, waiter);
            }
        });
        return waiter.completeOnTimeout(Optional.empty(), wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The player reached a backend: forgets their marks, and a kick that is waiting goes on
     * without a mark. The server that kicked them stays known for the rest of {@link #WINDOW}, so
     * a report that comes later than {@link #WAIT} still takes the moved player off the network.
     *
     * @param player the player
     */
    public void arrived(UUID player) {
        CompletableFuture<Optional<String>> waiter;
        synchronized (this) {
            marks.remove(player);
            waiter = waiting.remove(player);
        }
        if (waiter != null) {
            waiter.complete(Optional.empty());
        }
    }

    /**
     * Forgets the player's marks and kicks: they left the proxy. A kick that is waiting goes on
     * without a mark.
     *
     * @param player the player
     */
    public void forget(UUID player) {
        CompletableFuture<Optional<String>> waiter;
        synchronized (this) {
            marks.remove(player);
            kicks.remove(player);
            waiter = waiting.remove(player);
        }
        if (waiter != null) {
            waiter.complete(Optional.empty());
        }
    }

    private Timed live(Map<UUID, Timed> map, UUID player) {
        Timed entry = map.get(player);
        if (entry != null && !clock.instant().isBefore(entry.until())) {
            map.remove(player);
            return null;
        }
        return entry;
    }
}
