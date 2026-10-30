package net.vectolyn.cloud.node.channel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.vectolyn.cloud.network.ChannelListener;
import net.vectolyn.cloud.network.CloudNetworkServer;
import net.vectolyn.cloud.network.ServiceEvent;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceListener;
import net.vectolyn.cloud.node.service.ServiceManager;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Message;
import net.vectolyn.protocol.message.NetworkKick;
import net.vectolyn.protocol.message.PlayerJoin;
import net.vectolyn.protocol.message.PlayerQuit;
import net.vectolyn.protocol.message.PlayerSwitch;
import net.vectolyn.protocol.message.RegisterServer;
import net.vectolyn.protocol.message.ServerCapacity;
import net.vectolyn.protocol.message.ServiceStatus;
import net.vectolyn.protocol.message.TransferPlayer;
import net.vectolyn.protocol.message.UnregisterServer;

/**
 * Connects the cloud channel with the service core, in both directions.
 *
 * <ul>
 *   <li>Channel to services ({@link ChannelListener}): {@code ServiceReady} from a bridge marks the
 *       service RUNNING ({@link ServiceManager#markReady}); the last {@code ServiceStatus} of each
 *       service is kept for {@code service list} and {@code service info}.</li>
 *   <li>Services to channel ({@link ServiceListener}): when a process has ended (STOPPED or
 *       CRASHED) its connection is closed with {@code SHUTDOWN} and its status is forgotten. The
 *       token itself is revoked by the service core at the same moment, so the connection cannot
 *       come back.</li>
 * </ul>
 * Server lists of the proxies: a backend that is RUNNING is sent to the running proxies of its kind
 * as {@code RegisterServer} (the main proxy gets every backend except PandaSpigot with
 * {@code legacy = false}, the legacy proxy only the PandaSpigot ones with {@code legacy = true}), and
 * as {@code UnregisterServer} once it stops. A proxy that connects or becomes RUNNING gets every
 * backend of its kind at once; after a reconnect it first gets an {@code UnregisterServer} for
 * every backend it was sent before that has ended while it was away. Backends of a group that is not wanted are never sent. The Velocity
 * bridge leaves the servers of {@code velocity.toml} alone, so a static backend that is listed
 * there already is not changed.
 *
 * <p>Player events from the proxies ({@code PlayerJoin}, {@code PlayerSwitch}, {@code PlayerQuit})
 * go into {@link NetworkPlayers}, with the static backend names of the proxy ({@code lobby}) mapped
 * to the service ({@code lobby-1}).
 *
 * <p>A {@code NetworkKick} of a backend goes on to the running proxies of its kind
 * ({@link #onEvent}); from any other service it is dropped.
 */
public final class ChannelBridge implements ChannelListener, ServiceListener {

    private static final System.Logger LOG = System.getLogger(ChannelBridge.class.getName());

    private final Services services;
    private final Ticker ticker;
    private final Map<ServiceId, ServiceStatus> statuses = new ConcurrentHashMap<>();
    /** Backends sent to the proxies, with the message, so they can be removed after they are gone. */
    private final Map<ServiceId, RegisterServer> registered = new ConcurrentHashMap<>();
    /** What each proxy was told, to withdraw what a reconnect left behind. */
    private final ProxyLists lists = new ProxyLists();
    /** Orders {@link #register}, {@link #unregister} and {@link #syncProxy}; only sends happen under it. */
    private final Object listLock = new Object();
    private final NetworkPlayers players;
    /** Backends being emptied: the proxies send no new player there. */
    private final Set<ServiceId> closed = ConcurrentHashMap.newKeySet();
    /** The slots last sent per backend, so a reload only resends what changed. */
    private final Map<ServiceId, Integer> sentSlots = new ConcurrentHashMap<>();
    /** Time between two looks at the players of a backend being emptied. */
    static final Duration DRAIN_POLL = Duration.ofMillis(250);
    /** How long a player who is still on a backend being emptied gets before his move is sent again. */
    static final Duration RETRY_FIRST = Duration.ofSeconds(2);
    /** The longest wait between two moves of the same player; the wait doubles up to it. */
    static final Duration RETRY_MAX = Duration.ofSeconds(8);
    private volatile Channel channel;
    private volatile PlayerListener playerListener;
    private volatile Consumer<ServiceStatus> statusListener;

    /**
     * Creates the bridge and registers it as listener of the service core.
     *
     * @param services the service core
     */
    public ChannelBridge(ServiceManager services) {
        this(Services.of(services), Ticker.SYSTEM);
        services.addListener(this);
    }

    /** For tests: a fake service core and a clock of its own; nothing is registered. */
    ChannelBridge(Services services, Ticker ticker) {
        this.services = Objects.requireNonNull(services, "services");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.players = new NetworkPlayers(services::backendNamed);
    }

    /** What the bridge asks the service core; {@link ServiceManager} in the node, a fake in tests. */
    interface Services {
        boolean markReady(ServiceId id);

        Optional<Boolean> proxyKind(ServiceId id);

        Optional<Boolean> backendKind(ServiceId backend);

        Optional<ServiceId> backendNamed(ServiceId proxy, ServiceId name);

        Optional<RegisterServer> registration(ServiceId backend);

        Map<ServiceId, RegisterServer> registrations(boolean legacy);

        List<ServiceId> runningProxies(boolean legacy);

        OptionalInt slots(ServiceId backend);

        /** Whether the service is known and has a process (or is about to get one) right now. */
        boolean hasProcess(ServiceId id);

        static Services of(ServiceManager manager) {
            Objects.requireNonNull(manager, "manager");
            return new Services() {
                @Override
                public boolean markReady(ServiceId id) {
                    return manager.markReady(id);
                }

                @Override
                public Optional<Boolean> proxyKind(ServiceId id) {
                    return manager.proxyKind(id);
                }

                @Override
                public Optional<Boolean> backendKind(ServiceId backend) {
                    return manager.backendKind(backend);
                }

                @Override
                public Optional<ServiceId> backendNamed(ServiceId proxy, ServiceId name) {
                    return manager.backendNamed(proxy, name);
                }

                @Override
                public Optional<RegisterServer> registration(ServiceId backend) {
                    return manager.registration(backend);
                }

                @Override
                public Map<ServiceId, RegisterServer> registrations(boolean legacy) {
                    return manager.registrations(legacy);
                }

                @Override
                public List<ServiceId> runningProxies(boolean legacy) {
                    return manager.runningProxies(legacy);
                }

                @Override
                public OptionalInt slots(ServiceId backend) {
                    return manager.slots(backend);
                }

                @Override
                public boolean hasProcess(ServiceId id) {
                    return manager.find(id).map(now -> now.state().hasProcess()).orElse(false);
                }
            };
        }
    }

    /** Where the bridge sends; the cloud network server in the node, a fake in tests. */
    interface Channel {
        boolean isConnected(ServiceId service);

        void send(ServiceId service, Message message);

        void disconnect(ServiceId service, Disconnect.Reason reason);

        static Channel of(CloudNetworkServer server) {
            Objects.requireNonNull(server, "server");
            return new Channel() {
                @Override
                public boolean isConnected(ServiceId service) {
                    return server.isConnected(service);
                }

                @Override
                public void send(ServiceId service, Message message) {
                    server.send(service, message);
                }

                @Override
                public void disconnect(ServiceId service, Disconnect.Reason reason) {
                    server.disconnect(service, reason);
                }
            };
        }
    }

    /** The time of {@link #drain}; the system clock in the node, a fake in tests. */
    interface Ticker {
        /** The system clock and {@link Thread#sleep}. */
        Ticker SYSTEM = new Ticker() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                Thread.sleep(duration.toMillis());
            }
        };

        long nanoTime();

        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Returns the players online, as the proxies report them.
     *
     * @return the directory
     */
    public NetworkPlayers players() {
        return players;
    }

    /**
     * Sets the network server whose connections the bridge closes when a process ends.
     *
     * @param value the server
     */
    public void attach(CloudNetworkServer value) {
        attach(Channel.of(value));
    }

    /** For tests: a fake channel. */
    void attach(Channel value) {
        this.channel = Objects.requireNonNull(value, "value");
    }

    /**
     * Returns the last status report of a service, if it sent one during its current start.
     *
     * @param id the service
     * @return the report
     */
    public Optional<ServiceStatus> status(ServiceId id) {
        return Optional.ofNullable(statuses.get(id));
    }

    /**
     * Tells whether a service is connected to the channel right now.
     *
     * @param id the service
     * @return whether it has an authenticated connection
     */
    public boolean isConnected(ServiceId id) {
        Channel current = channel;
        return current != null && current.isConnected(id);
    }

    @Override
    public void onEvent(ServiceEvent event) {
        switch (event) {
            case ServiceEvent.Ready ready -> {
                if (services.markReady(ready.service())) {
                    LOG.log(System.Logger.Level.INFO, "{0} reported that it is ready", ready.service());
                }
            }
            case ServiceEvent.StatusReported reported -> {
                statuses.put(reported.service(), reported.status());
                Consumer<ServiceStatus> listener = statusListener;
                if (listener != null) {
                    listener.accept(reported.status());
                }
            }
            case ServiceEvent.Connected connected -> {
                LOG.log(System.Logger.Level.DEBUG, "{0} connected to the cloud channel", connected.service());
                syncProxy(connected.service());
            }
            case ServiceEvent.Disconnected disconnected ->
                    LOG.log(System.Logger.Level.DEBUG, "{0} left the cloud channel ({1})", disconnected.service(),
                            disconnected.reason() == null ? "connection lost" : disconnected.reason());
            case ServiceEvent.MessageReceived(var sender, NetworkKick kick) -> networkKick(sender, kick);
            // Only the sender and the outcome are logged, never the content (player names).
            case ServiceEvent.MessageReceived received -> {
                Optional<NetworkPlayers.Player> before = received.message() instanceof PlayerQuit quit
                        ? players.find(quit.uuid()) : Optional.empty();
                NetworkPlayers.Outcome outcome = players.apply(received.service(), received.message());
                if (outcome != NetworkPlayers.Outcome.APPLIED) {
                    LOG.log(System.Logger.Level.DEBUG, "A message of {0} on the cloud channel was dropped: {1}",
                            received.service(), outcome);
                } else {
                    playerChanged(received.message(), before);
                }
            }
        }
    }

    /**
     * A backend marked a kick as a punishment that takes the player off the network. It is passed
     * on only if the sender is a backend on the proxies' lists (not a proxy, not an unknown
     * service): with the backend's name on the proxy, to every running proxy of its kind. A proxy
     * that does not have the player ignores it. The network layer has already checked that the
     * message names the sender itself.
     */
    private void networkKick(ServiceId sender, NetworkKick kick) {
        Optional<Boolean> legacy = services.backendKind(sender);
        Optional<RegisterServer> listed = services.registration(sender);
        if (legacy.isEmpty() || listed.isEmpty() || !kick.server().equals(sender)) {
            LOG.log(System.Logger.Level.WARNING, "A network kick from {0} was dropped: it is no backend on the"
                    + " proxies'' lists", sender);
            return;
        }
        NetworkKick forward = new NetworkKick(kick.uuid(), listed.get().name(), kick.reason());
        int sent = 0;
        for (ServiceId proxy : services.runningProxies(legacy.get())) {
            if (send(proxy, forward)) {
                sent++;
            }
        }
        LOG.log(System.Logger.Level.INFO, "{0} kicked a player off the network; passed on to {1} proxies", sender,
                sent);
    }

    /** Told about every player event the bridge applied; for the API's event stream. */
    @FunctionalInterface
    public interface PlayerListener {
        /**
         * A player joined, switched or left.
         *
         * @param kind    {@code join}, {@code switch} or {@code leave}
         * @param player  the player; for {@code leave} as it was before
         */
        void changed(String kind, NetworkPlayers.Player player);
    }

    /**
     * Sets who hears about player events (one listener; the API). Calls come on the channel's
     * event thread and must return quickly.
     *
     * @param value the listener
     */
    public void playerListener(PlayerListener value) {
        this.playerListener = Objects.requireNonNull(value, "value");
    }

    /**
     * Sets who hears every status report of a service (one listener; the monitor). Calls come on
     * the channel's event thread and must return quickly.
     *
     * @param value the listener
     */
    public void statusListener(Consumer<ServiceStatus> value) {
        this.statusListener = Objects.requireNonNull(value, "value");
    }

    private void playerChanged(Message message, Optional<NetworkPlayers.Player> before) {
        PlayerListener listener = playerListener;
        if (listener == null) {
            return;
        }
        try {
            if (message instanceof PlayerJoin join) {
                players.find(join.uuid()).ifPresent(p -> listener.changed("join", p));
            } else if (message instanceof PlayerSwitch change) {
                players.find(change.uuid()).ifPresent(p -> listener.changed("switch", p));
            } else if (message instanceof PlayerQuit) {
                before.ifPresent(p -> listener.changed("leave", p));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "A player listener failed: {0}", e.toString());
        }
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        switch (service.state()) {
            case RUNNING -> {
                if (services.proxyKind(service.id()).isPresent()) {
                    syncProxy(service.id());
                } else {
                    services.registration(service.id()).ifPresent(message -> register(service.id(), message));
                }
                return;
            }
            case STOPPING, STOPPED, CRASHED -> unregister(service.id());
            default -> {
                return;
            }
        }
        if (service.state() == ServiceState.STOPPING) {
            return;
        }
        // Events arrive a moment later; if the service has been started again meanwhile, its new
        // connection must stay.
        if (services.hasProcess(service.id())) {
            return;
        }
        statuses.remove(service.id());
        players.proxyEnded(service.id());
        lists.proxyEnded(service.id());
        Channel current = channel;
        if (current != null) {
            current.disconnect(service.id(), Disconnect.Reason.SHUTDOWN);
        }
    }

    /** Sends a backend to every running proxy of its kind, with its slots. */
    private void register(ServiceId backend, RegisterServer message) {
        synchronized (listLock) {
            registered.put(backend, message);
            for (ServiceId proxy : services.runningProxies(message.legacy())) {
                if (send(proxy, message)) {
                    lists.sent(proxy, message.name());
                    sendCapacity(proxy, backend, message);
                }
            }
        }
    }

    /** {@code ServerCapacity} of a backend to one proxy, if its slots are known. Holds {@code listLock}. */
    private void sendCapacity(ServiceId proxy, ServiceId backend, RegisterServer message) {
        OptionalInt slots = services.slots(backend);
        if (slots.isPresent()) {
            send(proxy, new ServerCapacity(message.name(), slots.getAsInt(), !closed.contains(backend)));
            sentSlots.put(backend, slots.getAsInt());
        }
    }

    /** Sends the capacity of every listed backend again; for a changed {@code players-per-service}. */
    public void refreshCapacities() {
        synchronized (listLock) {
            for (Map.Entry<ServiceId, RegisterServer> entry : registered.entrySet()) {
                OptionalInt slots = services.slots(entry.getKey());
                if (slots.isPresent() && !Integer.valueOf(slots.getAsInt()).equals(sentSlots.get(entry.getKey()))) {
                    for (ServiceId proxy : services.runningProxies(entry.getValue().legacy())) {
                        sendCapacity(proxy, entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    private void announceCapacity(ServiceId backend) {
        synchronized (listLock) {
            RegisterServer message = registered.get(backend);
            if (message != null) {
                for (ServiceId proxy : services.runningProxies(message.legacy())) {
                    sendCapacity(proxy, backend, message);
                }
            }
        }
    }

    /**
     * Whether a proxy that lists this backend is connected right now, so its player count of the
     * backend can be trusted.
     *
     * @param backend the backend
     * @return whether such a proxy is connected
     */
    public boolean proxyWatches(ServiceId backend) {
        Optional<Boolean> kind = services.backendKind(backend);
        return kind.isPresent() && services.runningProxies(kind.get()).stream().anyMatch(this::isConnected);
    }

    /**
     * Empties a backend before it stops: the proxies send no new player there, every player on it is
     * moved ({@code TransferPlayer}) to the other open backends of its group, the emptiest first
     * ({@link TransferPlanner}), and the call waits until the proxies report none left. A player
     * still on it {@link #RETRY_FIRST} after his move was sent gets it again, with a wait that
     * doubles up to {@link #RETRY_MAX}: a proxy may have lost or refused the first one. The move is
     * sent again to the same backend as long as that one is open and has room. If players
     * are left after {@code timeout} (a move failed, no other backend), the backend is opened again.
     * Nobody is kicked.
     *
     * @param backend the backend
     * @param timeout how long to wait for the moves
     * @return {@link DrainResult#EMPTY} if no player is left on it, otherwise why not
     * @throws InterruptedException if the wait is interrupted; the backend is opened again
     */
    public DrainResult drain(ServiceId backend, Duration timeout) throws InterruptedException {
        Optional<RegisterServer> listed = services.registration(backend);
        if (listed.isEmpty()) {
            // Not on the proxies' lists: nobody can be moved away from it.
            return isEmpty(backend) ? DrainResult.EMPTY : DrainResult.NO_TARGET;
        }
        closed.add(backend);
        announceCapacity(backend);
        boolean empty = false;
        try {
            Map<UUID, Sent> sent = new HashMap<>();
            long deadline = ticker.nanoTime() + timeout.toNanos();
            while (true) {
                // Read again on every look: a player who reached the server while it was being
                // closed is moved as well, and one whose move did not happen is sent again.
                if (!moveAway(backend, listed.get(), sent, ticker.nanoTime())) {
                    // Players on it and nowhere to go: no need to wait for moves that are never sent.
                    return DrainResult.NO_TARGET;
                }
                empty = isEmpty(backend);
                if (empty) {
                    return DrainResult.EMPTY;
                }
                if (ticker.nanoTime() - deadline >= 0) {
                    return DrainResult.TIMEOUT;
                }
                ticker.sleep(DRAIN_POLL);
            }
        } finally {
            if (!empty) {
                reopen(backend);
            }
        }
    }

    /** How {@link #drain} ended. */
    public enum DrainResult {
        /** No player is left on the backend; it stays closed until it stops or is opened again. */
        EMPTY,
        /**
         * Players are on it and no other open backend of its group can take them, or it is not on
         * the proxies' lists; it is open again.
         */
        NO_TARGET,
        /** Players were still on it when the time was up; it is open again. */
        TIMEOUT
    }

    /**
     * When the move of a player was last sent and where to: the next one is due at {@code dueAt}
     * (a {@link Ticker#nanoTime} value), after {@code waitNanos}.
     */
    private record Sent(ServiceId to, long dueAt, long waitNanos) {

        /** After one more send at {@code now}: the wait doubles, up to {@link ChannelBridge#RETRY_MAX}. */
        Sent next(ServiceId target, long now) {
            long wait = Math.min(waitNanos * 2, RETRY_MAX.toNanos());
            return new Sent(target, now + wait, wait);
        }

        static Sent first(ServiceId target, long now) {
            return new Sent(target, now + RETRY_FIRST.toNanos(), RETRY_FIRST.toNanos());
        }
    }

    /**
     * Sends {@code TransferPlayer} for every player on the backend whose move was not sent yet or
     * is due again. A player who has left the backend is forgotten.
     *
     * @return false if players are on it and no other backend of the group can take them
     */
    private boolean moveAway(ServiceId backend, RegisterServer listed, Map<UUID, Sent> sent, long now) {
        List<NetworkPlayers.Player> present = players.on(backend);
        Set<UUID> still = new HashSet<>();
        present.forEach(p -> still.add(p.uuid()));
        sent.keySet().retainAll(still);
        List<NetworkPlayers.Player> on = present.stream().filter(p -> {
            Sent last = sent.get(p.uuid());
            return last == null || now - last.dueAt() >= 0;
        }).toList();
        if (on.isEmpty()) {
            return true;
        }
        List<TransferPlanner.Target> targets = new ArrayList<>();
        for (Map.Entry<ServiceId, RegisterServer> other : services.registrations(listed.legacy()).entrySet()) {
            OptionalInt slots = services.slots(other.getKey());
            if (!other.getKey().equals(backend) && !closed.contains(other.getKey()) && slots.isPresent()
                    && other.getValue().group().equals(listed.group())) {
                targets.add(new TransferPlanner.Target(other.getValue().name(), players.on(other.getKey()).size(),
                        slots.getAsInt()));
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        Map<UUID, ServiceId> moves = plan(on, targets, sent);
        int again = 0;
        for (NetworkPlayers.Player player : on) {
            ServiceId to = moves.get(player.uuid());
            if (to != null && send(player.proxy(), new TransferPlayer(player.uuid(), to,
                    TransferPlayer.Reason.SCALE_DOWN))) {
                Sent last = sent.get(player.uuid());
                sent.put(player.uuid(), last == null ? Sent.first(to, now) : last.next(to, now));
                if (last != null) {
                    again++;
                }
            }
        }
        if (again > 0) {
            LOG.log(System.Logger.Level.DEBUG, "{0} players on {1} were sent their move again", again, backend);
        }
        return true;
    }

    /**
     * Where the players go. A player whose move is sent again keeps the target of his last one as
     * long as it is still a target (open, same group) with room left, counting the players kept
     * there: a late proxy then finishes the first move instead of starting a second hop. Everyone
     * else is planned by {@link TransferPlanner} on the loads with the kept players counted.
     */
    private static Map<UUID, ServiceId> plan(List<NetworkPlayers.Player> on, List<TransferPlanner.Target> targets,
                                             Map<UUID, Sent> sent) {
        Map<ServiceId, int[]> load = new HashMap<>();
        Map<ServiceId, Integer> slots = new HashMap<>();
        for (TransferPlanner.Target target : targets) {
            load.put(target.name(), new int[] {target.players()});
            slots.put(target.name(), target.slots());
        }
        Map<UUID, ServiceId> moves = new HashMap<>();
        List<UUID> rest = new ArrayList<>();
        for (NetworkPlayers.Player player : on) {
            Sent last = sent.get(player.uuid());
            int[] count = last == null ? null : load.get(last.to());
            if (count != null && count[0] < slots.get(last.to())) {
                count[0]++;
                moves.put(player.uuid(), last.to());
            } else {
                rest.add(player.uuid());
            }
        }
        if (!rest.isEmpty()) {
            List<TransferPlanner.Target> counted = new ArrayList<>();
            for (TransferPlanner.Target target : targets) {
                counted.add(new TransferPlanner.Target(target.name(), load.get(target.name())[0], target.slots()));
            }
            moves.putAll(TransferPlanner.assign(rest, counted));
        }
        return moves;
    }

    /**
     * Empty means nobody on it for the proxies and 0 players in the backend's own last report: a
     * player who was just routed there and is not in one of the two lists yet keeps it running.
     */
    private boolean isEmpty(ServiceId backend) {
        ServiceStatus status = statuses.get(backend);
        return players.on(backend).isEmpty() && status != null && status.playersOnline() == 0;
    }

    /**
     * Opens a backend that was closed for a scale-down again, for example because its stop failed.
     *
     * @param backend the backend
     */
    public void reopen(ServiceId backend) {
        if (closed.remove(backend)) {
            announceCapacity(backend);
        }
    }

    /**
     * Removes a backend that was sent before from the proxies of its kind. A proxy that is not
     * connected right now keeps it in its list; {@link #syncProxy} withdraws it on the reconnect.
     */
    private void unregister(ServiceId backend) {
        synchronized (listLock) {
            RegisterServer message = registered.remove(backend);
            closed.remove(backend);
            sentSlots.remove(backend);
            if (message == null) {
                return;
            }
            for (ServiceId proxy : services.runningProxies(message.legacy())) {
                if (send(proxy, new UnregisterServer(message.name()))) {
                    lists.withdrawn(proxy, message.name());
                }
            }
        }
    }

    /**
     * Brings a proxy's list up to date: first withdraws what it may still list from before a
     * reconnect although the backend is gone, then sends every backend of its kind (repeats change
     * nothing in the Velocity bridge).
     */
    private void syncProxy(ServiceId proxy) {
        Optional<Boolean> legacy = services.proxyKind(proxy);
        if (legacy.isEmpty()) {
            return;
        }
        // Runs on a channel thread (Connected) or the service event thread (RUNNING), while
        // register() and unregister() run on the latter: without the lock a backend that becomes
        // RUNNING between the snapshot below and stale() would be withdrawn right after it was sent.
        synchronized (listLock) {
            Map<ServiceId, RegisterServer> current = services.registrations(legacy.get());
            Set<ServiceId> names = new HashSet<>();
            current.values().forEach(message -> names.add(message.name()));
            for (ServiceId stale : lists.stale(proxy, names)) {
                if (send(proxy, new UnregisterServer(stale))) {
                    lists.withdrawn(proxy, stale);
                }
            }
            for (Map.Entry<ServiceId, RegisterServer> entry : current.entrySet()) {
                registered.put(entry.getKey(), entry.getValue());
                if (send(proxy, entry.getValue())) {
                    lists.sent(proxy, entry.getValue().name());
                    sendCapacity(proxy, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private boolean send(ServiceId proxy, Message message) {
        Channel current = channel;
        if (current != null && current.isConnected(proxy)) {
            current.send(proxy, message);
            return true;
        }
        return false;
    }

    @Override
    public void output(ServiceId service, String line) {
        // Output goes to the console and the log files, not to the channel.
    }
}
