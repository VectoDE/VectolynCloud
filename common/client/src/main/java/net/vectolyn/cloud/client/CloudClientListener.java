package net.vectolyn.cloud.client;

/**
 * Receives connection events and the messages the cloud sends to this service. The platform
 * bridges implement it, for example to add a server to the proxy on {@code RegisterServer}.
 *
 * <p>Runs on the client's event thread, one event at a time, never on a server thread. Hand work
 * that touches the server to its scheduler. An exception is logged and ignored.
 */
@FunctionalInterface
public interface CloudClientListener {

    /** Handles one event; ignore the kinds you do not need. */
    void onEvent(CloudClientEvent event);
}
