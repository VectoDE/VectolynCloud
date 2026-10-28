package net.vectora.cloud.console.command;

import java.util.Objects;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.Text;

/**
 * A command line cannot be split into words. The message names the rule and the position, never
 * the text that was typed, so a secret typed at the prompt by mistake does not end up in a log.
 */
public final class CommandSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Which rule was broken. */
    public enum Reason {
        /** A quote is not closed. The detail is the position of the opening quote (0-based). */
        UNCLOSED_QUOTE(Text.of("Unclosed quote at position %1$d.", "Nicht geschlossenes Anführungszeichen an Position %1$d.")),
        /** A backslash at the very end. The detail is its position. */
        DANGLING_ESCAPE(Text.of("Backslash at the end of the line (position %1$d).",
                "Backslash am Zeilenende (Position %1$d).")),
        /** A control character or line break. The detail is its position. */
        CONTROL_CHARACTER(Text.of("Control character at position %1$d is not allowed.",
                "Steuerzeichen an Position %1$d ist nicht erlaubt.")),
        /** The line is too long. The detail is the limit. */
        TOO_LONG(Text.of("The line is longer than %1$d characters.", "Die Zeile ist länger als %1$d Zeichen.")),
        /** Too many words. The detail is the limit. */
        TOO_MANY_WORDS(Text.of("More than %1$d words.", "Mehr als %1$d Wörter."));

        private final Text text;

        Reason(Text text) {
            this.text = text;
        }

        /** The message template; its argument is {@link CommandSyntaxException#detail()}. */
        public Text text() {
            return text;
        }
    }

    private final Reason reason;
    private final int detail;

    /** A broken rule; {@code detail} is a position or limit, see {@link Reason}. */
    public CommandSyntaxException(Reason reason, int detail) {
        super(Objects.requireNonNull(reason, "reason").text().format(Language.EN, detail));
        this.reason = reason;
        this.detail = detail;
    }

    /** Which rule was broken. */
    public Reason reason() {
        return reason;
    }

    /** Position (0-based) or limit, depending on {@link #reason()}. */
    public int detail() {
        return detail;
    }

    /** The message in {@code language}. */
    public String message(Language language) {
        return reason.text().format(language, detail);
    }
}
