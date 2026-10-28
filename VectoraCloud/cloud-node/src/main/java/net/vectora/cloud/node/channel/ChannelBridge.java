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
import net.vectora.protocol.message.ServiceStatus;

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
 * Player events from the proxies arrive as {@link ServiceEvent.MessageReceived}; they are not
 * used yet (stage 2 PR 13 checks them against the node's state).
 */
public final class ChannelBridge implements ChannelListener, ServiceListener {

    private static final System.Logger LOG = System.getLogger(ChannelBridge.class.getName());

    private final ServiceManager services;
    private final Map<ServiceId, ServiceStatus> statuses = new ConcurrentHashMap<>();
    private volatile CloudNetworkServer server;

    /**
     * Creates the bridge and registers it as listener of the service core.
     *
     * @param services the service core
     */
    public ChannelBridge(ServiceManager services) {
        this.services = Objects.requireNonNull(services, "services");
        services.addListener(this);
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
            case ServiceEvent.Connected connected ->
                    LOG.log(System.Logger.Level.DEBUG, "{0} connected to the cloud channel", connected.service());
            case ServiceEvent.Disconnected disconnected ->
                    LOG.log(System.Logger.Level.DEBUG, "{0} left the cloud channel ({1})", disconnected.service(),
                            disconnected.reason() == null ? "connection lost" : disconnected.reason());
            case ServiceEvent.MessageReceived received -> {
                // Player events: used from stage 2 PR 13 on.
            }
        }
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        if (service.state() != ServiceState.STOPPED && service.state() != ServiceState.CRASHED) {
            return;
        }
        // Events arrive a moment later; if the service has been started again meanwhile, its new
        // connection must stay.
        if (services.find(service.id()).map(now -> now.state().hasProcess()).orElse(false)) {
            return;
        }
        statuses.remove(service.id());
        CloudNetworkServer current = server;
        if (current != null) {
            current.disconnect(service.id(), Disconnect.Reason.SHUTDOWN);
        }
    }

    @Override
    public void output(ServiceId service, String line) {
        // Output goes to the console and the log files, not to the channel.
    }
}
