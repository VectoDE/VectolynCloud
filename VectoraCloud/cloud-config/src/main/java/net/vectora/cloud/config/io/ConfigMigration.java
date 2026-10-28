package net.vectora.cloud.config.io;

import tools.jackson.databind.node.ObjectNode;

/**
 * Upgrades the raw content of one file from {@link #fromVersion()} to {@code fromVersion() + 1}.
 *
 * <p>A migration works on the YAML tree before it is bound to the model, so it can rename, move or
 * drop fields that the current model no longer knows. It must not touch {@code schema-version};
 * {@link ConfigFormat} sets that after each step. A file several versions behind runs through each
 * step in turn.
 */
public interface ConfigMigration {

    /** Version this migration reads. */
    int fromVersion();

    /** Changes {@code root} in place so that it matches version {@code fromVersion() + 1}. */
    void migrate(ObjectNode root);
}
