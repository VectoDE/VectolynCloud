package net.vectora.cloud.network;

import java.net.InetSocketAddress;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.ServiceStatus;

/**
 * Something a service did or that happened to its connection, as passed to
 * {@link ChannelListener#onEvent}. The set is closed, so a {@code switch} over it can cover every
 * case:
 * <pre>{@code
 * switch (event) {
 *     case ServiceEvent.Connected connected -> ...
 *     case ServiceEvent.StatusReported(ServiceStatus status) -> ...
 *     default -> { }
 * }
 * }</pre>
 */
public sealed interface ServiceEvent {

    /** The authenticated service the event is about. */
    ServiceId service();

    /**
     * A service has authenticated. If it reconnects while an older connection is still open, the
     * older one is closed with {@code REPLACED} and this event comes again for the new one.
     *
     * @param service the service
     * @param remote the address it connected from, {@code null} if the transport has none
     */
    record Connected(ServiceId service, InetSocketAddress remote) implements ServiceEvent {
    }

    /**
     * The service reports that it has started and accepts players.
     *
     * @param service the service
     */
    record Ready(ServiceId service) implements ServiceEvent {
    }

    /**
     * A status report of the service. The id in the report is the authenticated one; a report
     * about another service closes the connection instead.
     *
     * @param status the report
     */
    record StatusReported(ServiceStatus status) implements ServiceEvent {

        /** Returns the id in the report. */
        @Override
        public ServiceId service() {
            return status.service();
        }
    }

    /**
     * Any other message the network layer does not handle itself, currently {@code PlayerJoin},
     * {@code PlayerQuit} and {@code PlayerSwitch} from the proxies and {@code NetworkKick} from the
     * backends (only for the sender's own service id).
     *
     * @param service the sender
     * @param message the message
     */
    record MessageReceived(ServiceId service, Message message) implements ServiceEvent {
    }

    /**
     * The connection of a service has ended. Not sent for a connection that a newer one of the
     * same service replaced.
     *
     * @param service the service
     * @param reason the reason the service gave or the cloud closed with, {@code null} if the
     *     connection simply broke
     */
    record Disconnected(ServiceId service, Disconnect.Reason reason) implements ServiceEvent {
    }
}
