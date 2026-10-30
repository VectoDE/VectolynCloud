package net.vectolyn.cloud.config.operation;

import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Records that the operator accepted the Minecraft EULA ({@code eula.txt} is written only after
 * this). Has no arguments on purpose: there is no operation to "un-accept" through the API.
 */
public record AcceptEula() implements ConfigOperation {

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withEulaAccepted(true));
    }
}
