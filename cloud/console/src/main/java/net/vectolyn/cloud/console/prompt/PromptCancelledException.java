package net.vectolyn.cloud.console.prompt;

import java.util.Objects;

/** A question was not answered: the operator cancelled, the input ended, or too many wrong answers. */
public final class PromptCancelledException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the question ended without an answer. */
    public enum Reason {
        /** Ctrl+C, or {@value Prompter#CANCEL_WORD} typed. */
        CANCELLED,
        /** Ctrl+D or the end of stdin. */
        END_OF_INPUT,
        /** {@value Prompter#MAX_ATTEMPTS} invalid answers in a row. */
        TOO_MANY_ATTEMPTS
    }

    private final Reason reason;

    /** Ended for {@code reason}. */
    public PromptCancelledException(Reason reason) {
        super("question not answered: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /** Why. */
    public Reason reason() {
        return reason;
    }
}
