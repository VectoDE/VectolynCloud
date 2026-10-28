package net.vectora.cloud.config.operation;

import java.util.Objects;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.ProxyConfig;

/**
 * Replaces the listener settings of the main proxy.
 *
 * @param proxy new settings, checked by the validator
 */
public record SetProxy(ProxyConfig proxy) implements ConfigOperation {

    /** Checks the argument. */
    public SetProxy {
        Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withProxy(proxy));
    }
}
