package net.vectora.cloud.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a caught kick from turning into a loop.
 *
 * <ul>
 *   <li>Per incident every server once: the servers a player was kicked from and sent to stay
 *       excluded until {@link #WINDOW} after the last caught kick.</li>
 *   <li>At most {@link #MAX_RESCUES} caught kicks per player within {@link #WINDOW}; after that the
 *       player is disconnected with the original reason.</li>
 *   <li>Leaving the proxy ends both.</li>
 * </ul>
 * Thread-safe. No platform type.
 */
public final class KickGuard {

    /** Caught kicks per player within {@link #WINDOW}. */
    public static final int MAX_RESCUES = 3;

    /** The time window of the limit and of an incident. */
    public static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final Map<UUID, Incident> incidents = new HashMap<>();

    /** Creates a guard with the system clock. */
    public KickGuard() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a guard.
     *
     * @param clock for the window
     */
    public KickGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Whether another kick of the player may be caught.
     *
     * @param player the player
     * @return {@code false} once {@link #MAX_RESCUES} were caught within {@link #WINDOW}
     */
    public synchronized boolean mayRescue(UUID player) {
        Incident incident = current(player);
        return incident == null || incident.times.size() < MAX_RESCUES;
    }

    /**
     * The servers already tried in the player's current incident.
     *
     * @param player the player
     * @return the server names, empty without an incident
     */
    public synchronized Set<String> tried(UUID player) {
        Incident incident = current(player);
        return incident == null ? Set.of() : Set.copyOf(incident.servers);
    }

    /**
     * A kick was caught.
     *
     * @param player     the player
     * @param kickedFrom the server that kicked
     * @param target     the server the player is sent to
     */
    public synchronized void rescued(UUID player, String kickedFrom, String target) {
        Incident incident = current(player);
        if (incident == null) {
            incident = new Incident();
            incidents.put(player, incident);
        }
        incident.times.addLast(clock.instant());
        incident.servers.add(kickedFrom);
        incident.servers.add(target);
    }

    /**
     * The player left the proxy.
     *
     * @param player the player
     */
    public synchronized void forget(UUID player) {
        incidents.remove(player);
    }

    /** The player's incident with expired kicks dropped, or {@code null} if none is running. */
    private Incident current(UUID player) {
        Incident incident = incidents.get(player);
        if (incident == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(incident.times.getLast().plus(WINDOW))) {
            incidents.remove(player);
            return null;
        }
        while (!now.isBefore(incident.times.getFirst().plus(WINDOW))) {
            incident.times.removeFirst();
        }
        return incident;
    }

    private static final class Incident {
        final Deque<Instant> times = new ArrayDeque<>();
        final Set<String> servers = new HashSet<>();
    }
}
