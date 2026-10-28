package net.vectora.cloud.launcher;

import java.io.Serial;

/** Thrown by {@link ArgumentParser} for an unknown, repeated or incomplete argument. */
public final class ArgumentException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Which mistake was made; the launcher turns it into a message in the user's language. */
    public enum Kind {
        /** The argument is not an option of the launcher. */
        UNKNOWN,
        /** An option that takes a value was given without one. */
        MISSING_VALUE,
        /** The option was given more than once. */
        DUPLICATE,
        /** A switch was given a value with {@code =}. */
        UNEXPECTED_VALUE,
        /** The value is not allowed, for example {@code --lang fr}. */
        INVALID_VALUE
    }

    private final Kind kind;
    private final String argument;

    /**
     * Creates the exception.
     *
     * @param kind     what went wrong
     * @param argument the offending argument as typed
     */
    public ArgumentException(Kind kind, String argument) {
        super(kind + ": " + argument);
        this.kind = kind;
        this.argument = argument;
    }

    /**
     * Returns what went wrong.
     *
     * @return the kind of mistake
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the offending argument as typed.
     *
     * @return the argument
     */
    public String argument() {
        return argument;
    }
}
