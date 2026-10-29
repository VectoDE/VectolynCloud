package net.vectora.cloud.node.channel;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServiceStatus;
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
 * backend of its kind at once. Backends of a group that is not wanted are never sent. The Velocity
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
    private final NetworkPlayers players;
    private volatile CloudNetworkServer server;

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
            case ServiceEvent.StatusReported reported -> statuses.put(reported.service(), reported.status());
            case ServiceEvent.Connected connected -> {
                LOG.log(System.Logger.Level.DEBUG, "{0} connected to the cloud channel", connected.service());
                syncProxy(connected.service());
            }
            case ServiceEvent.Disconnected disconnected ->
                    LOG.log(System.Logger.Level.DEBUG, "{0} left the cloud channel ({1})", disconnected.service(),
                            disconnected.reason() == null ? "connection lost" : disconnected.reason());
            // Only the sender and the outcome are logged, never the content (player names).
            case ServiceEvent.MessageReceived received -> {
                NetworkPlayers.Outcome outcome = players.apply(received.service(), received.message());
                if (outcome != NetworkPlayers.Outcome.APPLIED) {
                    LOG.log(System.Logger.Level.DEBUG, "A message of {0} on the cloud channel was dropped: {1}",
                            received.service(), outcome);
                }
            }
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
        CloudNetworkServer current = server;
        if (current != null) {
            current.disconnect(service.id(), Disconnect.Reason.SHUTDOWN);
        }
    }

    /** Sends a backend to every running proxy of its kind. */
    private void register(ServiceId backend, RegisterServer message) {
        registered.put(backend, message);
        for (ServiceId proxy : services.runningProxies(message.legacy())) {
            send(proxy, message);
        }
    }

    /** Removes a backend that was sent before from the proxies of its kind. */
    private void unregister(ServiceId backend) {
        RegisterServer message = registered.remove(backend);
        if (message == null) {
            return;
        }
        for (ServiceId proxy : services.runningProxies(message.legacy())) {
            send(proxy, new UnregisterServer(message.name()));
        }
    }

    /** Sends a proxy every backend of its kind; repeats change nothing in the Velocity bridge. */
    private void syncProxy(ServiceId proxy) {
        Optional<Boolean> legacy = services.proxyKind(proxy);
        if (legacy.isEmpty()) {
            return;
        }
        for (Map.Entry<ServiceId, RegisterServer> entry : services.registrations(legacy.get()).entrySet()) {
            registered.put(entry.getKey(), entry.getValue());
            send(proxy, entry.getValue());
        }
    }

    private void send(ServiceId proxy, Message message) {
        CloudNetworkServer current = server;
        if (current != null && current.isConnected(proxy)) {
            current.send(proxy, message);
        }
    }

    @Override
    public void output(ServiceId service, String line) {
        // Output goes to the console and the log files, not to the channel.
    }
}
