package net.vectora.cloud.api.backend;

import java.util.Optional;

/**
 * Everything the API needs from the node besides the configuration (which it changes through
 * {@code ConfigService.apply} directly). cloud-node implements the parts and hands them to
 * {@code ApiServer.builder(...)}; see {@code docs/cloud/api.md#wiring-in-cloud-node}.
 *
 * <p>The interfaces are small on purpose, so the API never sees a process, a path or a token of a
 * service.
 */
public interface ApiBackend {

    /**
     * The services.
     *
     * @return service access
     */
    ServiceAccess services();

    /**
     * Status, setup and shutdown of the node.
     *
     * @return node access
     */
    NodeAccess node();

    /**
     * Backups, once cloud-backup is wired in. Empty means the backup endpoints answer 501.
     *
     * @return backup access, or empty
     */
    default Optional<BackupAccess> backups() {
        return Optional.empty();
    }

    /**
     * Removes secrets from a text before it leaves the API: output lines, messages of
     * {@link ApiBackendException}. The node passes {@code RedactingFilter::redact} with every value
     * of {@code secrets/vectora.env} and the service tokens. Output lines are already redacted by
     * the node; this is the second line of defence.
     *
     * @param text a text for a client
     * @return the text with every secret replaced by {@code ***}
     */
    default String redact(String text) {
        return text;
    }
}
