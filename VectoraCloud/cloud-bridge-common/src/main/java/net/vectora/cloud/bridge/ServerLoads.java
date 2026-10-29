package net.vectora.cloud.bridge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Which group each backend of the proxy belongs to, how many players it is meant for, and which
 * one a player who enters a group goes to: the backend with the lowest load in percent, that is
 * players on it divided by its slots.
 *
 * <ul>
 *   <li>The group comes from {@code RegisterServer}, the slots and whether the backend takes new
 *       players from {@code ServerCapacity}. A backend without slots, a closed one (the cloud is
 *       emptying it) and one that is not in the proxy's server list are never chosen.</li>
 *   <li>Players on a backend are what the proxy itself sees, plus players it has just sent there
 *       and who have not arrived yet ({@link #PENDING}). Without that, a burst of logins in the
 *       same moment would all go to the same backend.</li>
 *   <li>Equal load: fewer players first, then the name, so the choice is stable.</li>
 * </ul>
 * Thread-safe. No platform type, so it is tested without a proxy.
 */
public final class ServerLoads {

    /** How long a player who was sent to a backend counts there before the proxy reports them. */
    public static final Duration PENDING = Duration.ofSeconds(10);

    private final Clock clock;
    private final Map<String, Entry> servers = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    /** Creates an empty directory with the system clock. */
    public ServerLoads() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an empty directory.
     *
     * @param clock for the pending players
     */
    public ServerLoads(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A backend is in the proxy's server list, in this group. Its slots stay unknown until
     * {@link #capacity} (or are kept if it was known before under the same group).
     *
     * @param name  server name on the proxy
     * @param group its group
     */
    public synchronized void registered(String name, String group) {
        Entry previous = servers.get(name);
        servers.put(name, previous != null && previous.group().equals(group) ? previous : new Entry(group, 0, false));
    }

    /**
     * Sets the slots and the open flag of a known backend; an unknown one is ignored.
     *
     * @param name  server name on the proxy
     * @param slots players it is meant for, 1 or more
     * @param open  whether new players may be sent there
     * @return whether the backend was known
     */
    public synchronized boolean capacity(String name, int slots, boolean open) {
        Entry entry = servers.get(name);
        if (entry == null || slots < 1) {
            return false;
        }
        servers.put(name, new Entry(entry.group(), slots, open));
        return true;
    }

    /**
     * The backend left the proxy's server list.
     *
     * @param name server name on the proxy
     */
    public synchronized void removed(String name) {
        servers.remove(name);
        pending.values().removeIf(p -> p.server().equals(name));
    }

    /**
     * The group of a backend.
     *
     * @param name server name on the proxy
     * @return the group, if the cloud named one
     */
    public synchronized Optional<String> groupOf(String name) {
        return Optional.ofNullable(servers.get(name)).map(Entry::group);
    }

    /**
     * Whether a backend may take a new player: known slots, open.
     *
     * @param name server name on the proxy
     * @return whether it is open
     */
    public synchronized boolean isOpen(String name) {
        Entry entry = servers.get(name);
        return entry != null && entry.slots() > 0 && entry.open();
    }

    /**
     * Chooses the backend of a group with the lowest load and counts the player there until they
     * arrive.
     *
     * @param player  the player to send
     * @param group   the group the player enters
     * @param exclude backends that must not be chosen (the one the player was kicked from)
     * @param listed  whether the proxy has a server of that name right now
     * @param players players the proxy sees on a backend
     * @return the backend, or empty if no backend of the group is known, open and listed
     */
    public synchronized Optional<String> choose(UUID player, String group, Set<String> exclude,
                                                Predicate<String> listed, ToIntFunction<String> players) {
        Objects.requireNonNull(player, "player");
        Instant now = clock.instant();
        expire(now);
        pending.remove(player);
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Entry> e : servers.entrySet()) {
            Entry entry = e.getValue();
            String name = e.getKey();
            if (entry.group().equals(group) && entry.slots() > 0 && entry.open() && !exclude.contains(name)
                    && listed.test(name)) {
                counts.put(name, Math.max(0, players.applyAsInt(name)));
            }
        }
        for (Pending p : pending.values()) {
            counts.computeIfPresent(p.server(), (name, count) -> count + 1);
        }
        Optional<String> chosen = counts.keySet().stream().min(Comparator
                .<String>comparingDouble(name -> (double) counts.get(name) / servers.get(name).slots())
                .thenComparingInt(counts::get)
                .thenComparing(Comparator.naturalOrder()));
        chosen.ifPresent(name -> pending.put(player, new Pending(name, now.plus(PENDING))));
        return chosen;
    }

    /**
     * The player reached a backend or left the proxy; they no longer count as on their way.
     *
     * @param player the player
     */
    public synchronized void arrived(UUID player) {
        pending.remove(player);
    }

    private void expire(Instant now) {
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            if (!now.isBefore(it.next().until())) {
                it.remove();
            }
        }
    }

    private record Entry(String group, int slots, boolean open) {
    }

    private record Pending(String server, Instant until) {
    }
}
