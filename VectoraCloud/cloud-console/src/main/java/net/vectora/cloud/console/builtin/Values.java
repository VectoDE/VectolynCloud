package net.vectora.cloud.console.builtin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;

/**
 * Turns typed text into values with the same strictness as the YAML files: {@code true} or
 * {@code false} only, whole numbers only, enum values in lower case. A wrong value becomes a
 * {@link ConfigError} with {@code invalid-value} and the expected format; the value itself is not
 * repeated.
 */
final class Values {

    private Values() {
    }

    static boolean bool(String file, String path, String text) throws ConfigException {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(file, path, "true or false");
        };
    }

    static int integer(String file, String path, String text) throws ConfigException {
        if (!text.matches("-?[0-9]{1,9}")) {
            throw invalid(file, path, "a whole number");
        }
        return Integer.parseInt(text);
    }

    static <E extends Enum<E>> E enumValue(String file, String path, String text, Class<E> type) throws ConfigException {
        for (E constant : type.getEnumConstants()) {
            if (name(constant).equals(text)) {
                return constant;
            }
        }
        throw invalid(file, path, String.join(", ", names(type)));
    }

    /** The lower-case file spelling of an enum constant. */
    static String name(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Values::name).toList();
    }

    /** {@code ""} clears an optional text; anything else is kept. */
    static String optionalText(String text) {
        return text.isEmpty() ? null : text;
    }

    static ConfigException invalid(String file, String path, String expected) {
        return new ConfigException(ConfigError.of(file, path, ErrorCode.INVALID_VALUE, expected));
    }

    /** {@code on} or {@code off}, as in {@code group wanted lobby on}. */
    static boolean onOff(String file, String path, String text) throws ConfigException {
        return switch (text) {
            case "on" -> true;
            case "off" -> false;
            default -> throw invalid(file, path, "on or off");
        };
    }
}
