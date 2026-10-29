package net.vectora.cloud.node.channel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;

/**
 * The players online in the network, as the proxies report them. Thread-safe.
 *
 * <p>A proxy names backends as they appear in its server list: the first static service of a group
 * under the group name ({@code lobby}), every other one under its id. The resolver turns that name
 * into the service id ({@code lobby-1}), so everything here refers to real services. A report is
 * dropped without effect if it does not fit:
 * <ul>
 *   <li>the sender is no proxy, or the name means no backend of that proxy;</li>
 *   <li>a switch whose {@code from} is not where the player is, or a quit of a player the proxy
 *       did not report as joined;</li>
 *   <li>a join of a player who is already online through another proxy.</li>
 * </ul>
 */
public final class NetworkPlayers {

    /**
     * One player.
     *
     * @param uuid    the player's id
     * @param name    the name of the join
     * @param proxy   the proxy the player connected through
     * @param service the backend the player is on
     * @param bedrock whether the player connected through Geyser
     */
    public record Player(UUID uuid, String name, ServiceId proxy, ServiceId service, boolean bedrock) {
    }

    /** What happened to a report. */
    public enum Outcome {
        /** Applied. */
        APPLIED,
        /** Not a player event. */
        NOT_A_PLAYER_EVENT,
        /** Dropped: the sender is no proxy or the server name means no backend of it. */
        UNKNOWN_SERVER,
        /** Dropped: it does not fit what is known about the player. */
        INCONSISTENT
    }

    private final BiFunction<ServiceId, ServiceId, Optional<ServiceId>> resolver;
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();

    /**
     * Creates the directory.
     *
     * @param resolver maps (proxy, name in its server list) to the backend service, empty if the
     *                 sender is no proxy or the name means no backend of it
     */
    public NetworkPlayers(BiFunction<ServiceId, ServiceId, Optional<ServiceId>> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Applies a report of a proxy.
     *
     * @param proxy   the authenticated sender
     * @param message the message
     * @return what happened
     */
    public synchronized Outcome apply(ServiceId proxy, Message message) {
        switch (message) {
            case PlayerJoin join -> {
                Optional<ServiceId> to = resolver.apply(proxy, join.service());
                if (to.isEmpty()) {
                    return Outcome.UNKNOWN_SERVER;
                }
                Player known = players.get(join.uuid());
                if (known != null && !known.proxy().equals(proxy)) {
                    return Outcome.INCONSISTENT;
                }
                players.put(join.uuid(), new Player(join.uuid(), join.name(), proxy, to.get(), join.bedrock()));
                return Outcome.APPLIED;
            }
            case PlayerSwitch change -> {
                Optional<ServiceId> from = resolver.apply(proxy, change.from());
                Optional<ServiceId> to = resolver.apply(proxy, change.to());
                if (to.isEmpty()) {
                    return Outcome.UNKNOWN_SERVER;
                }
                Player known = players.get(change.uuid());
                if (known == null || !known.proxy().equals(proxy) || from.isEmpty()
                        || !known.service().equals(from.get())) {
                    return Outcome.INCONSISTENT;
                }
                players.put(change.uuid(), new Player(known.uuid(), known.name(), proxy, to.get(), known.bedrock()));
                return Outcome.APPLIED;
            }
            case PlayerQuit quit -> {
                Player known = players.get(quit.uuid());
                if (known == null || !known.proxy().equals(proxy)) {
                    return Outcome.INCONSISTENT;
                }
                players.remove(quit.uuid());
                return Outcome.APPLIED;
            }
            default -> {
                return Outcome.NOT_A_PLAYER_EVENT;
            }
        }
    }

    /**
     * Forgets every player of a proxy that has ended: they are not online any more.
     *
     * @param proxy the proxy
     */
    public synchronized void proxyEnded(ServiceId proxy) {
        players.values().removeIf(player -> player.proxy().equals(proxy));
    }

    /**
     * Looks up a player.
     *
     * @param uuid the player's id
     * @return the player, if online
     */
    public Optional<Player> find(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    /**
     * The players on one backend.
     *
     * @param service the backend
     * @return the players, in no particular order
     */
    public List<Player> on(ServiceId service) {
        return players.values().stream().filter(player -> player.service().equals(service)).toList();
    }

    /**
     * Every player online.
     *
     * @return the players, in no particular order
     */
    public List<Player> online() {
        return List.copyOf(players.values());
    }
}
