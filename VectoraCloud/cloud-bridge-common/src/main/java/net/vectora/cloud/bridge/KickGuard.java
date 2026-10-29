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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

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
     * Checks the limit, plans the move and records it as one step, so kicks of the same player
     * on several threads at once can never catch more than {@link #MAX_RESCUES} or pick a server
     * another of them just tried. {@code plan} runs under the guard's lock; it must not call back
     * into the guard.
     *
     * @param player     the player
     * @param kickedFrom the server that kicked
     * @param plan       gets the servers to skip (those tried in the incident and
     *                   {@code kickedFrom}) and returns the plan, or empty if there is none
     * @param sentTo     the server a plan sends the player to, recorded as tried
     * @param <T>        the kind of plan
     * @return the plan, or why there is none
     */
    public synchronized <T> Attempt<T> rescue(UUID player, String kickedFrom,
                                              Function<Set<String>, Optional<T>> plan, Function<T, String> sentTo) {
        Objects.requireNonNull(kickedFrom, "kickedFrom");
        if (!mayRescue(player)) {
            return new Attempt<>(true, Optional.empty());
        }
        Set<String> exclude = new HashSet<>(tried(player));
        exclude.add(kickedFrom);
        Optional<T> result = plan.apply(Set.copyOf(exclude));
        result.ifPresent(r -> rescued(player, kickedFrom, sentTo.apply(r)));
        return new Attempt<>(false, result);
    }

    /**
     * What {@link #rescue} found.
     *
     * @param limited whether the player was moved {@link #MAX_RESCUES} times within {@link #WINDOW}
     *                already; then nothing was planned
     * @param result  the plan, empty when limited or when there was none
     * @param <T>     the kind of plan
     */
    public record Attempt<T>(boolean limited, Optional<T> result) {

        /** Checks the parts. */
        public Attempt {
            Objects.requireNonNull(result, "result");
        }
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
