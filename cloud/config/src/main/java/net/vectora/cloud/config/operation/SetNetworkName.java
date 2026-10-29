package net.vectora.cloud.config.operation;

import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * Sets the display name of the network.
 *
 * @param networkName new name, checked by the validator
 */
public record SetNetworkName(String networkName) implements ConfigOperation {

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withNetworkName(networkName));
    }
}
