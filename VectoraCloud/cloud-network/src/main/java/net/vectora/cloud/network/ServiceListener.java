package net.vectora.cloud.network;

import java.net.InetSocketAddress;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.ServiceStatus;

/**
 * Receives what the services report. This is the narrow seam between the network layer and the
 * service core in {@code cloud-node}: the node implements it and keeps its own state.
 *
 * <p>Every method runs on a network thread and must return quickly; hand longer work to an
 * executor of your own. An exception is logged and does not close the connection. All methods
 * do nothing by default.
 */
public interface ServiceListener {

    /** A listener that ignores everything. */
    ServiceListener NONE = new ServiceListener() { };

    /**
     * A service has authenticated. If it reconnects while an older connection is still open, the
     * older one is closed with {@code REPLACED} and this is called again for the new one.
     */
    default void serviceConnected(ServiceId service, InetSocketAddress remote) {
    }

    /** The service reports that it has started and accepts players. */
    default void serviceReady(ServiceId service) {
    }

    /** A status report of the service; the id in it is the authenticated one. */
    default void serviceStatus(ServiceStatus status) {
    }

    /**
     * Any other message the network layer does not handle itself, currently {@code PlayerJoin},
     * {@code PlayerQuit} and {@code PlayerSwitch} from the proxies.
     */
    default void messageReceived(ServiceId service, Message message) {
    }

    /**
     * The connection of a service has ended. Not called for a connection that a newer one of the
     * same service replaced.
     *
     * @param reason the reason the service gave, or the reason the cloud closed it with
     */
    default void serviceDisconnected(ServiceId service, Disconnect.Reason reason) {
    }
}
