package net.vectora.cloud.api.backend;

import net.vectora.cloud.config.model.ConfigSnapshot;

/** State of the node: status, setup and shutdown. Implemented by cloud-node. */
public interface NodeAccess {

    /**
     * The current status.
     *
     * @return the status
     */
    NodeStatus status();

    /**
     * Whether the node is shutting down. The API then refuses to start services, to reload the
     * configuration and to make backups (503 {@code shutting-down}).
     *
     * @return true once the shutdown has begun
     */
    boolean shuttingDown();

    /**
     * Where the first setup stands. The default reads {@code cloud.yml}; the node may add steps
     * of the setup assistant.
     *
     * @param snapshot the current configuration
     * @return the status
     */
    default SetupStatus setup(ConfigSnapshot snapshot) {
        return SetupStatus.of(snapshot);
    }
}
