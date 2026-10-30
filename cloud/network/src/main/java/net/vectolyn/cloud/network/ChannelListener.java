package net.vectolyn.cloud.network;

/**
 * Receives what the services report over the cloud channel. This is the narrow seam between the
 * network layer and the service core in {@code cloud-node}: the node implements it and keeps its
 * own state.
 *
 * <p>Not to be confused with {@code net.vectolyn.cloud.node.service.ServiceListener}, which reports
 * state changes and output lines of the server processes. This one only sees the channel:
 * connections, {@code ServiceReady}, status reports and player events.
 *
 * <p>Called on a network thread and must return quickly; hand longer work to an executor of your
 * own. Events of one connection arrive in order. An exception is logged and does not close the
 * connection. Without a listener ({@link CloudNetworkServer.Builder#listener} not called) the
 * events are dropped.
 */
@FunctionalInterface
public interface ChannelListener {

    /** Handles one event; ignore the kinds you do not need. */
    void onEvent(ServiceEvent event);
}
