package net.vectora.cloud.node.channel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.vectora.cloud.network.ChannelListener;
import net.vectora.cloud.network.CloudNetworkServer;
import net.vectora.cloud.network.ServiceEvent;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceListener;
import net.vectora.cloud.node.service.ServiceManager;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerQuit;
import net.vectora.protocol.message.PlayerSwitch;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.ServiceStatus;
import net.vectora.protocol.message.TransferPlayer;
import net.vectora.protocol.message.UnregisterServer;

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
 */
public final class ChannelBridge implements ChannelListener, ServiceListener {

    private static final System.Logger LOG = System.getLogger(ChannelBridge.class.getName());

    private final ServiceManager services;
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
    private volatile CloudNetworkServer server;
    private volatile PlayerListener playerListener;
    private volatile java.util.function.Consumer<ServiceStatus> statusListener;

    /**
     * Creates the bridge and registers it as listener of the service core.
     *
     * @param services the service core
     */
    public ChannelBridge(ServiceManager services) {
        this.services = Objects.requireNonNull(services, "services");
        this.players = new NetworkPlayers(services::backendNamed);
        services.addListener(this);
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
        this.server = Objects.requireNonNull(value, "value");
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
        CloudNetworkServer current = server;
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
                java.util.function.Consumer<ServiceStatus> listener = statusListener;
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
    public void statusListener(java.util.function.Consumer<ServiceStatus> value) {
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
        if (services.find(service.id()).map(now -> now.state().hasProcess()).orElse(false)) {
            return;
        }
        statuses.remove(service.id());
        players.proxyEnded(service.id());
        lists.proxyEnded(service.id());
        CloudNetworkServer current = server;
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
     * ({@link TransferPlanner}), and the call waits until the proxies report none left. If players
     * are left after {@code timeout} (a move failed, no other backend), the backend is opened again.
     * Nobody is kicked.
     *
     * @param backend the backend
     * @param timeout how long to wait for the moves
     * @return whether no player is left on it
     * @throws InterruptedException if the wait is interrupted; the backend is opened again
     */
    public boolean drain(ServiceId backend, Duration timeout) throws InterruptedException {
        Optional<RegisterServer> listed = services.registration(backend);
        if (listed.isEmpty()) {
            return players.on(backend).isEmpty();
        }
        closed.add(backend);
        announceCapacity(backend);
        boolean empty = false;
        try {
            List<NetworkPlayers.Player> on = players.on(backend);
            List<TransferPlanner.Target> targets = new ArrayList<>();
            for (Map.Entry<ServiceId, RegisterServer> other : services.registrations(listed.get().legacy()).entrySet()) {
                OptionalInt slots = services.slots(other.getKey());
                if (!other.getKey().equals(backend) && !closed.contains(other.getKey()) && slots.isPresent()
                        && other.getValue().group().equals(listed.get().group())) {
                    targets.add(new TransferPlanner.Target(other.getValue().name(), players.on(other.getKey()).size(),
                            slots.getAsInt()));
                }
            }
            if (!on.isEmpty() && targets.isEmpty()) {
                // Nowhere to go: no need to wait for moves that are never sent.
                return false;
            }
            Map<java.util.UUID, ServiceId> moves = TransferPlanner.assign(
                    on.stream().map(NetworkPlayers.Player::uuid).toList(), targets);
            for (NetworkPlayers.Player player : on) {
                ServiceId to = moves.get(player.uuid());
                if (to != null) {
                    send(player.proxy(), new TransferPlayer(player.uuid(), to, TransferPlayer.Reason.SCALE_DOWN));
                }
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!(empty = players.on(backend).isEmpty()) && System.nanoTime() < deadline) {
                Thread.sleep(DRAIN_POLL.toMillis());
            }
            return empty;
        } finally {
            if (!empty) {
                closed.remove(backend);
                announceCapacity(backend);
            }
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
            java.util.Set<ServiceId> names = new java.util.HashSet<>();
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
        CloudNetworkServer current = server;
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
