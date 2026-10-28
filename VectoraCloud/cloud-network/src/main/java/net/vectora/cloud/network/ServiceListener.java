package net.vectora.cloud.network;

/**
 * Receives what the services report. This is the narrow seam between the network layer and the
 * service core in {@code cloud-node}: the node implements it and keeps its own state.
 *
 * <p>Called on a network thread and must return quickly; hand longer work to an executor of your
 * own. Events of one connection arrive in order. An exception is logged and does not close the
 * connection. Without a listener ({@link CloudNetworkServer.Builder#listener} not called) the
 * events are dropped.
 */
@FunctionalInterface
public interface ServiceListener {

    /** Handles one event; ignore the kinds you do not need. */
    void onEvent(ServiceEvent event);
}
