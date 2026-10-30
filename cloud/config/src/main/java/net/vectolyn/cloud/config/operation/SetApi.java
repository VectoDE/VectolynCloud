package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ApiConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Replaces the API settings.
 *
 * @param api new settings, checked by the validator
 */
public record SetApi(ApiConfig api) implements ConfigOperation {

    /** Checks the argument. */
    public SetApi {
        Objects.requireNonNull(api, "api");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withApi(api));
    }
}
