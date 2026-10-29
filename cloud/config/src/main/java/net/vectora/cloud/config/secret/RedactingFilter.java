package net.vectora.cloud.config.secret;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Replaces every known secret value in a text with {@code ***}. The log filter of the node and the
 * console output build on this.
 *
 * <p>Values shorter than {@link #MIN_LENGTH} characters are ignored: masking every {@code 1} or
 * {@code ab} in a log would destroy it and still not protect such a weak value. Longer values are
 * replaced first, so a secret that contains another one is masked as a whole. Immutable and
 * thread-safe; build a new filter when the secrets change.
 */
public final class RedactingFilter {

    /** Minimum length of a value that is masked. */
    public static final int MIN_LENGTH = 4;

    private static final RedactingFilter NONE = new RedactingFilter(List.of());

    private final List<String> values;

    private RedactingFilter(List<String> values) {
        this.values = values;
    }

    /** A filter for {@code secrets}. */
    public static RedactingFilter of(Collection<Secret> secrets) {
        List<String> values = secrets.stream()
                .filter(Objects::nonNull)
                .map(Secret::reveal)
                .filter(v -> v.length() >= MIN_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return values.isEmpty() ? NONE : new RedactingFilter(values);
    }

    /** A filter for every value in {@code file}. */
    public static RedactingFilter of(EnvFile file) {
        return of(file.entries().stream().map(EnvFile.Entry::value).toList());
    }

    /** A filter that changes nothing. */
    public static RedactingFilter none() {
        return NONE;
    }

    /** {@code text} with every known secret replaced by {@value Secret#MASK}; null stays null. */
    public String redact(String text) {
        if (text == null || values.isEmpty()) {
            return text;
        }
        String result = text;
        for (String value : values) {
            if (result.contains(value)) {
                result = result.replace(value, Secret.MASK);
            }
        }
        return result;
    }

    /** Number of values this filter masks. */
    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "RedactingFilter[" + values.size() + " values]";
    }
}
