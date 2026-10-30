package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.Language;

/**
 * Sets the language of console, setup assistant and messages.
 *
 * @param language new language
 */
public record SetLanguage(Language language) implements ConfigOperation {

    /** Checks the argument. */
    public SetLanguage {
        Objects.requireNonNull(language, "language");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return current.withCloud(current.cloud().withLanguage(language));
    }
}
