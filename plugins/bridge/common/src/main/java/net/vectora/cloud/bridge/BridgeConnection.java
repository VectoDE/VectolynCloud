package net.vectora.cloud.bridge;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.vectora.cloud.client.CloudClient;
import net.vectora.cloud.client.CloudClientListener;
import net.vectora.cloud.client.CloudClientSettings;

/**
 * Start-up of a bridge: read the settings the cloud passed, create the client and the sender.
 *
 * <p>Nothing here blocks: {@code CloudClient.create} only creates its threads, and the connect
 * itself ({@code start}) is queued on the sender thread. So a platform may call
 * {@link #open} from its main thread in {@code onEnable}.
 */
public final class BridgeConnection {

    private static final System.Logger LOG = System.getLogger(BridgeConnection.class.getName());

    private BridgeConnection() {
    }

    /**
     * Reads the settings from the environment (system properties win, for manual tests).
     *
     * @return empty, after logging why, if the server was not started by VectoraCloud
     */
    public static Optional<CloudClientSettings> settings() {
        return settings(System::getenv, System::getProperty);
    }

    /** Like {@link #settings()}, with the two sources given, for tests. */
    public static Optional<CloudClientSettings> settings(UnaryOperator<String> environment,
            UnaryOperator<String> properties) {
        try {
            return Optional.of(CloudClientSettings.from(environment, properties));
        } catch (IllegalStateException e) {
            // The message names the variable and never contains the token.
            LOG.log(Level.WARNING, "VectoraCloud bridge stays inactive: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Creates the client for {@code settings}, a sender for it and the listener from
     * {@code listenerFactory}, installs the client's shutdown hook and queues the connect.
     *
     * @param listenerFactory builds the listener for messages from the cloud from the sender, or
     *     returns {@code null} if the bridge does not need one
     * @return the sender; close it in the platform's disable or shutdown hook
     */
    public static BridgeSender open(CloudClientSettings settings,
            Function<BridgeSender, CloudClientListener> listenerFactory) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(listenerFactory, "listenerFactory");
        CloudClient client = CloudClient.create(settings);
        BridgeSender sender = BridgeSender.start(new CloudClientLink(client));
        CloudClientListener listener = listenerFactory.apply(sender);
        if (listener != null) {
            client.addListener(listener);
        }
        client.installShutdownHook();
        sender.startLink();
        LOG.log(Level.INFO, "VectoraCloud bridge for {0} connects to {1}:{2}", settings.service().value(),
                settings.host(), String.valueOf(settings.port()));
        return sender;
    }
}
