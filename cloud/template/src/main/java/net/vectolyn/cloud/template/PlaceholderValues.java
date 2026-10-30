package net.vectolyn.cloud.template;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Source of the values for {@code {{VECTOLYN_*}}} placeholders.
 *
 * <p>The cloud fills this from its configuration and from {@code secrets/}. The renderer only asks
 * for names that occur in a template; a name without a value is an error, never an empty string.
 */
@FunctionalInterface
public interface PlaceholderValues {

    /**
     * Looks up the value of a placeholder.
     *
     * @param name placeholder name without braces, e.g. {@code VECTOLYN_SERVER_PORT}
     * @return the value, or empty if this source has none
     */
    Optional<String> find(String name);

    /**
     * Returns values that ask this source first and {@code fallback} second.
     *
     * @param fallback source for names this one does not know
     * @return the combined source
     */
    default PlaceholderValues orElse(PlaceholderValues fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return name -> {
            Optional<String> value = find(name);
            return value.isPresent() ? value : fallback.find(name);
        };
    }

    /**
     * Returns values backed by a copy of {@code values}.
     *
     * @param values placeholder names without braces mapped to their values
     * @return the source
     */
    static PlaceholderValues of(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name));
    }

    /**
     * Returns a source without any value.
     *
     * @return the empty source
     */
    static PlaceholderValues none() {
        return name -> Optional.empty();
    }
}
