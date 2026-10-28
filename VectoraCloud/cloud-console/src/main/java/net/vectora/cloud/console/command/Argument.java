package net.vectora.cloud.console.command;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectora.cloud.console.Text;

/**
 * A positional argument of a command.
 *
 * @param name        shown in usage lines ({@code <name>}); lower case, digits and {@code -}
 * @param description shown by {@code help <command>}
 * @param optional    whether it may be left out; only trailing arguments may be optional
 * @param rest        whether it takes every remaining word (only the last argument)
 * @param suggestions tab completion candidates
 */
public record Argument(String name, Text description, boolean optional, boolean rest, Suggestions suggestions) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** Completion candidates for an argument. */
    @FunctionalInterface
    public interface Suggestions {
        /** No suggestions. */
        Suggestions NONE = words -> List.of();

        /**
         * Candidates for the argument.
         *
         * @param previous the words before it, including the command and subcommand names
         */
        List<String> suggest(List<String> previous);

        /** Always the same candidates. */
        static Suggestions of(List<String> values) {
            List<String> copy = List.copyOf(values);
            return words -> copy;
        }
    }

    /** Checks the name. */
    public Argument {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("argument names are lower case, digits and -: " + name);
        }
        Objects.requireNonNull(description, "description");
        suggestions = suggestions == null ? Suggestions.NONE : suggestions;
    }

    /** A required argument. */
    public static Argument required(String name, Text description) {
        return new Argument(name, description, false, false, Suggestions.NONE);
    }

    /** An optional argument. */
    public static Argument optional(String name, Text description) {
        return new Argument(name, description, true, false, Suggestions.NONE);
    }

    /** Same argument with completion candidates. */
    public Argument suggest(Suggestions values) {
        return new Argument(name, description, optional, rest, values);
    }

    /** Same argument with fixed completion candidates. */
    public Argument suggest(List<String> values) {
        return suggest(Suggestions.of(values));
    }

    /** Same argument, taking every remaining word. */
    public Argument takingRest() {
        return new Argument(name, description, optional, true, suggestions);
    }

    /** How the argument appears in a usage line: {@code <name>}, {@code [name]}, {@code <name...>}. */
    public String usage() {
        String inner = name + (rest ? "..." : "");
        return optional ? "[" + inner + "]" : "<" + inner + ">";
    }
}
