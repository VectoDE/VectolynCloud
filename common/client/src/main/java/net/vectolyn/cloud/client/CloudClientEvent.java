package net.vectolyn.cloud.client;

import net.vectolyn.protocol.message.AuthResult;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Message;

/**
 * A connection event or a message from the cloud, as passed to
 * {@link CloudClientListener#onEvent}. The set is closed, so a {@code switch} over it can cover
 * every case:
 * <pre>{@code
 * client.addListener(event -> {
 *     if (event instanceof CloudClientEvent.MessageReceived(RegisterServer register)) {
 *         // add the server to the proxy
 *     }
 * });
 * }</pre>
 */
public sealed interface CloudClientEvent {

    /** The cloud has accepted this service. Comes again after every reconnect. */
    record Connected() implements CloudClientEvent {
    }

    /**
     * The connection has ended; the client reconnects on its own unless it was closed.
     *
     * @param reason the reason the cloud gave, or {@code null} if the connection broke without one
     */
    record Disconnected(Disconnect.Reason reason) implements CloudClientEvent {
    }

    /**
     * The cloud refused the token or the id. The client keeps trying with growing waits.
     *
     * @param outcome the reason the cloud gave
     */
    record Rejected(AuthResult.Outcome outcome) implements CloudClientEvent {
    }

    /**
     * A message from the cloud that the client does not handle itself, such as
     * {@code RegisterServer}.
     *
     * @param message the message
     */
    record MessageReceived(Message message) implements CloudClientEvent {
    }
}
