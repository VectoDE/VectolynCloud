package net.vectora.cloud.client;

import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;

/**
 * Connection events and the messages the cloud sends to this service. The platform bridges
 * implement it, for example to add a server to the proxy on {@code RegisterServer}.
 *
 * <p>Methods run on the client's event thread, one at a time, never on a server thread. Hand
 * work that touches the server to its scheduler. An exception is logged and ignored. All
 * methods do nothing by default.
 */
public interface CloudClientListener {

    /** The cloud has accepted this service. Called again after every reconnect. */
    default void connected() {
    }

    /**
     * The connection has ended; the client reconnects on its own unless it was closed.
     *
     * @param reason the reason the cloud gave, or {@code null} if the connection broke without one
     */
    default void disconnected(Disconnect.Reason reason) {
    }

    /** The cloud refused the token or the id. The client keeps trying with growing waits. */
    default void rejected(AuthResult.Outcome outcome) {
    }

    /** A message from the cloud that the client does not handle itself, such as {@code RegisterServer}. */
    default void messageReceived(Message message) {
    }
}
