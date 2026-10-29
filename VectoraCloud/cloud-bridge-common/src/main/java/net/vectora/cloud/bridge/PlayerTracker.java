package net.vectora.cloud.bridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;

/**
 * The proxy's view of where each player is, and the player events it reports. The tracker does
 * not pass platform events on as they come; it checks them against what it has already reported,
 * so the cloud receives a consistent sequence per player: one {@code PlayerJoin}, any number of
 * {@code PlayerSwitch} with {@code from} equal to the last reported service, one
 * {@code PlayerQuit}.
 *
 * <ul>
 *   <li>A player counts as joined once the first backend accepted them. A player who leaves
 *       before that produces no event.</li>
 *   <li>The "previous server" of a platform event is not trusted. A switch is always reported
 *       from the service the tracker reported last, so a lost or reordered event cannot make the
 *       cloud's state diverge.</li>
 *   <li>A server that the proxy does not know, or whose name is not a valid service id, and a
 *       player name that the protocol does not allow, are ignored and change nothing.</li>
 *   <li>A connect to the server the player is already on, a second disconnect and a disconnect of
 *       an unknown player produce nothing.</li>
 * </ul>
 *
 * <p>State is kept per session: the platform's player object of one login, compared with
 * {@code equals} (Velocity's player objects use identity). Velocity fires the events of one
 * player on different threads, so they can arrive out of order:
 *
 * <ul>
 *   <li>A connect that arrives after the disconnect of its session is ignored. Ended sessions are
 *       remembered weakly, so they cost nothing once the platform drops the player object.</li>
 *   <li>A disconnect of an older session after a newer login with the same UUID is ignored.</li>
 *   <li>A connect of a newer session while an older one with the same UUID is still recorded
 *       (its disconnect is still on the way) ends the older one first: {@code PlayerQuit}, then
 *       {@code PlayerJoin}.</li>
 * </ul>
 *
 * <p>Bedrock players are recognised by Floodgate's UUID scheme (the upper 64 bits are zero), so
 * the bridge needs no Floodgate API.
 *
 * <p>Thread-safe: Velocity fires events on several threads. A caller that queues the returned
 * events while holding the tracker's monitor keeps their order across threads.
 */
public final class PlayerTracker {

    private final Predicate<String> knownServer;
    private final Map<UUID, Entry> players = new HashMap<>();
    private final Set<Object> ended = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Creates a tracker.
     *
     * @param knownServer whether the proxy has a server of that name registered
     */
    public PlayerTracker(Predicate<String> knownServer) {
        this.knownServer = Objects.requireNonNull(knownServer, "knownServer");
    }

    /**
     * A backend accepted the player.
     *
     * @param session the platform's player object of this login
     * @param uuid the player
     * @param name the player's name
     * @param server the server the player is on now
     * @return the events to report, in order; empty if there is nothing to report
     */
    public synchronized List<Message> connected(Object session, UUID uuid, String name, String server) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(uuid, "uuid");
        if (ended.contains(session)) {
            return List.of();
        }
        Optional<ServiceId> to = serviceId(server);
        if (to.isEmpty()) {
            return List.of();
        }
        Entry entry = players.get(uuid);
        if (entry != null && entry.session.equals(session)) {
            if (entry.service.equals(to.get())) {
                return List.of();
            }
            PlayerSwitch change = new PlayerSwitch(uuid, entry.name, entry.service, to.get(), isBedrock(uuid));
            entry.service = to.get();
            return List.of(change);
        }
        Optional<PlayerJoin> join = join(uuid, name, to.get());
        if (join.isEmpty()) {
            return List.of();
        }
        players.put(uuid, new Entry(session, join.get().name(), to.get()));
        if (entry == null) {
            return List.of(join.get());
        }
        // Another session of this UUID is still recorded: its disconnect has not arrived yet.
        return List.of(new PlayerQuit(uuid, entry.name, entry.service, isBedrock(uuid)), join.get());
    }

    /**
     * The player left the proxy.
     *
     * @param session the platform's player object of the login that ended
     * @param uuid the player
     * @return the {@code PlayerQuit} to report, if this session was reported as joined
     */
    public synchronized Optional<Message> disconnected(Object session, UUID uuid) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(uuid, "uuid");
        ended.add(session);
        Entry entry = players.get(uuid);
        if (entry == null || !entry.session.equals(session)) {
            return Optional.empty();
        }
        players.remove(uuid);
        return Optional.of(new PlayerQuit(uuid, entry.name, entry.service, isBedrock(uuid)));
    }

    /** Players reported as joined and not yet as quit. */
    public synchronized int size() {
        return players.size();
    }

    /** The service the player was last reported on. */
    public synchronized Optional<ServiceId> serviceOf(UUID uuid) {
        Entry entry = players.get(uuid);
        return entry == null ? Optional.empty() : Optional.of(entry.service);
    }

    /** Floodgate gives Bedrock players a UUID whose upper 64 bits are zero. */
    static boolean isBedrock(UUID uuid) {
        return uuid.getMostSignificantBits() == 0;
    }

    private static Optional<PlayerJoin> join(UUID uuid, String name, ServiceId to) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PlayerJoin(uuid, name, to, isBedrock(uuid)));
        } catch (IllegalArgumentException invalidName) {
            return Optional.empty();
        }
    }

    private Optional<ServiceId> serviceId(String server) {
        if (server == null || !knownServer.test(server)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ServiceId(server));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static final class Entry {
        private final Object session;
        private final String name;
        private ServiceId service;

        Entry(Object session, String name, ServiceId service) {
            this.session = session;
            this.name = name;
            this.service = service;
        }
    }
}
