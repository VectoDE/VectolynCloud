package net.vectora.cloud.config.operation;

import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.LegacyProxyConfig;

/**
 * Switches the legacy proxy for the 1.8.8 groups and sets its port.
 *
 * @param enabled whether the cloud runs it
 * @param port    its Java port, checked by the validator
 */
public record SetLegacyProxy(boolean enabled, int port) implements ConfigOperation {

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withLegacyProxy(new LegacyProxyConfig(enabled, port)));
    }
}
