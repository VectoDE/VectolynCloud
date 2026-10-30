package net.vectolyn.cloud.launcher;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the command line of {@code VectolynCloud.jar}. Hand-written on purpose: seven options do
 * not justify a third-party parser in the one module that must not depend on anything.
 *
 * <p>Options with a value accept both {@code --home /srv/cloud} and {@code --home=/srv/cloud}.
 * Every option may appear at most once. Anything else, including positional arguments, is an
 * error.
 */
public final class ArgumentParser {

    private ArgumentParser() {
    }

    /**
     * Parses the arguments.
     *
     * @param args raw command line arguments
     * @return the parsed arguments
     * @throws ArgumentException for an unknown, repeated or incomplete argument
     */
    public static LauncherArguments parse(String[] args) throws ArgumentException {
        boolean allowRoot = false;
        boolean nonInteractive = false;
        boolean help = false;
        boolean version = false;
        Optional<Path> home = Optional.empty();
        Optional<Path> setupFile = Optional.empty();
        Optional<Language> language = Optional.empty();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            String name = raw;
            String inlineValue = null;
            int eq = raw.indexOf('=');
            if (raw.startsWith("--") && eq > 2) {
                name = raw.substring(0, eq);
                inlineValue = raw.substring(eq + 1);
            }
            if (!isOption(name)) {
                throw new ArgumentException(ArgumentException.Kind.UNKNOWN, raw);
            }
            if (!seen.add(name)) {
                throw new ArgumentException(ArgumentException.Kind.DUPLICATE, name);
            }
            if (!takesValue(name)) {
                if (inlineValue != null) {
                    throw new ArgumentException(ArgumentException.Kind.UNEXPECTED_VALUE, raw);
                }
                switch (name) {
                    case "--allow-root" -> allowRoot = true;
                    case "--non-interactive" -> nonInteractive = true;
                    case "--help" -> help = true;
                    case "--version" -> version = true;
                    default -> throw new IllegalStateException(name);
                }
                continue;
            }
            String value = inlineValue;
            if (value == null) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new ArgumentException(ArgumentException.Kind.MISSING_VALUE, name);
                }
                value = args[++i];
            }
            if (value.isBlank()) {
                throw new ArgumentException(ArgumentException.Kind.MISSING_VALUE, name);
            }
            switch (name) {
                case "--home" -> home = Optional.of(path(name, value));
                case "--setup-file" -> setupFile = Optional.of(path(name, value));
                case "--lang" -> language = Optional.of(language(value));
                default -> throw new IllegalStateException(name);
            }
        }
        return new LauncherArguments(allowRoot, home, nonInteractive, setupFile, language, help, version);
    }

    private static boolean isOption(String name) {
        return switch (name) {
            case "--allow-root", "--non-interactive", "--help", "--version",
                 "--home", "--setup-file", "--lang" -> true;
            default -> false;
        };
    }

    private static boolean takesValue(String name) {
        return name.equals("--home") || name.equals("--setup-file") || name.equals("--lang");
    }

    private static Language language(String value) throws ArgumentException {
        Optional<Language> parsed = Language.parse(value);
        if (parsed.isEmpty()) {
            throw new ArgumentException(ArgumentException.Kind.INVALID_VALUE, "--lang " + value);
        }
        return parsed.get();
    }

    private static Path path(String name, String value) throws ArgumentException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new ArgumentException(ArgumentException.Kind.INVALID_VALUE, name + " " + value);
        }
    }
}
