package net.vectolyn.cloud.config.operation;

import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Marks the setup as complete. The validator rejects this while the EULA is not accepted.
 */
public record CompleteSetup() implements ConfigOperation {

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withSetupComplete(true));
    }
}
