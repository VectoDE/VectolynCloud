package net.vectora.cloud.config.error;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.vectora.cloud.config.model.Language;

/**
 * One problem in the configuration, structured so that console, setup assistant and API can show
 * it in either language.
 *
 * <p>Errors never carry the offending value: a mistyped secret pasted into the wrong field must
 * not end up in a log through its error message.
 *
 * @param file file relative to the cloud home, for example {@code cloud.yml} or {@code groups/lobby.yml}
 * @param path field path inside the file, for example {@code database.port} or {@code catalog[2].id};
 *             empty for the whole file
 * @param code what is wrong
 * @param args arguments for the message template of {@code code}
 * @param line line in the file (1-based), or 0 when unknown
 */
public record ConfigError(String file, String path, ErrorCode code, List<String> args, int line) {

    /** Validates and copies the arguments. */
    public ConfigError {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(code, "code");
        args = List.copyOf(args);
        if (line < 0) {
            throw new IllegalArgumentException("line must not be negative");
        }
    }

    /** An error without line information. */
    public static ConfigError of(String file, String path, ErrorCode code, Object... args) {
        return new ConfigError(file, path, code, strings(args), 0);
    }

    /** An error with line information from the parser. */
    public static ConfigError at(String file, String path, int line, ErrorCode code, Object... args) {
        return new ConfigError(file, path, code, strings(args), Math.max(0, line));
    }

    /** Message text in the given language, without file and path. */
    public String message(Language language) {
        return String.format(Locale.ROOT, code.template(language), args.toArray());
    }

    /**
     * Full line for console and logs, for example
     * {@code cloud.yml: database.port (line 7): Must be between 1 and 65535.}
     */
    public String describe(Language language) {
        StringBuilder out = new StringBuilder(file);
        if (!path.isEmpty()) {
            out.append(": ").append(path);
        }
        if (line > 0) {
            out.append(language == Language.DE ? " (Zeile " : " (line ").append(line).append(')');
        }
        return out.append(": ").append(message(language)).toString();
    }

    @Override
    public String toString() {
        return describe(Language.EN) + " [" + code.code() + "]";
    }

    private static List<String> strings(Object[] args) {
        return java.util.Arrays.stream(args).map(String::valueOf).toList();
    }
}
