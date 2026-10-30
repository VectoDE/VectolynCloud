package net.vectolyn.cloud.monitor.notify;

import java.io.IOException;
import net.vectolyn.cloud.monitor.alert.AlertEvent;

/**
 * Sends alerts somewhere outside the cloud. {@link WebhookNotifier} is the only built-in one; a
 * Discord bot, mail or anything else implements this interface. Called on the notification thread
 * of {@link NotificationDispatcher}, one event at a time; may block up to its own time limit.
 */
public interface Notifier extends AutoCloseable {

    /**
     * Sends one event.
     *
     * @param event what happened
     * @throws IOException if it could not be delivered; the message must not contain secrets
     *     (URLs, tokens), because it is logged
     */
    void send(AlertEvent event) throws IOException;

    /** Short name for the log, for example {@code webhook}. Never the URL. */
    String name();

    /** Releases connections; the default does nothing. */
    @Override
    default void close() {
    }
}
