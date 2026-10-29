package net.vectora.cloud.bridge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * <p>Bedrock players are recognised by Floodgate's UUID scheme (the upper 64 bits are zero), so
 * the bridge needs no Floodgate API.
 *
 * <p>Thread-safe: Velocity fires events on several threads.
 */
public final class PlayerTracker {

    private final Predicate<String> knownServer;
    private final Map<UUID, Entry> players = new HashMap<>();

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
     * @param uuid the player
     * @param name the player's name
     * @param server the server the player is on now
     * @return the event to report, if any
     */
    public synchronized Optional<Message> connected(UUID uuid, String name, String server) {
        Objects.requireNonNull(uuid, "uuid");
        Optional<ServiceId> to = serviceId(server);
        if (to.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = players.get(uuid);
        if (entry == null) {
            if (name == null) {
                return Optional.empty();
            }
            PlayerJoin join;
            try {
                join = new PlayerJoin(uuid, name, to.get(), isBedrock(uuid));
            } catch (IllegalArgumentException invalidName) {
                return Optional.empty();
            }
            players.put(uuid, new Entry(join.name(), to.get()));
            return Optional.of(join);
        }
        if (entry.service.equals(to.get())) {
            return Optional.empty();
        }
        PlayerSwitch change = new PlayerSwitch(uuid, entry.name, entry.service, to.get(), isBedrock(uuid));
        entry.service = to.get();
        return Optional.of(change);
    }

    /**
     * The player left the proxy.
     *
     * @return the {@code PlayerQuit} to report, if the player was reported as joined
     */
    public synchronized Optional<Message> disconnected(UUID uuid) {
        Entry entry = players.remove(Objects.requireNonNull(uuid, "uuid"));
        if (entry == null) {
            return Optional.empty();
        }
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
        private final String name;
        private ServiceId service;

        Entry(String name, ServiceId service) {
            this.name = name;
            this.service = service;
        }
    }
}
