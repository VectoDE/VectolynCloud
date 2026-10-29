package net.vectora.cloud.config.error;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;
import net.vectora.cloud.config.model.Language;

/**
 * The configuration could not be loaded, validated or changed. Carries every problem found, not
 * only the first one, so the operator can fix a file in one go.
 */
public final class ConfigException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Not serialized: {@link ConfigError} is a record of plain values, but this type is not meant to travel. */
    private final transient List<ConfigError> errors;

    /** Creates the exception from at least one error. */
    public ConfigException(List<ConfigError> errors) {
        super(summary(errors));
        this.errors = List.copyOf(errors);
    }

    /** Creates the exception from a single error. */
    public ConfigException(ConfigError error) {
        this(List.of(error));
    }

    /** Creates the exception from a single error with an underlying cause (for example an I/O error). */
    public ConfigException(ConfigError error, Throwable cause) {
        super(summary(List.of(error)), cause);
        this.errors = List.of(error);
    }

    /** All problems, in the order they were found. Never empty. */
    public List<ConfigError> errors() {
        return errors == null ? List.of() : errors;
    }

    /** All problems as lines in the given language. */
    public String describe(Language language) {
        return errors().stream().map(e -> e.describe(language)).collect(Collectors.joining(System.lineSeparator()));
    }

    private static String summary(List<ConfigError> errors) {
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("a ConfigException needs at least one error");
        }
        String first = errors.getFirst().toString();
        return errors.size() == 1 ? first : first + " (and " + (errors.size() - 1) + " more)";
    }
}
