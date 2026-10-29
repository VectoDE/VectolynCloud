package net.vectora.cloud.config.secret;

import java.io.Serial;
import java.util.Objects;
import net.vectora.cloud.config.model.Language;

/**
 * {@code vectora.env} is malformed. The message names the line and the rule that was broken, never
 * the content of the line, because that content is usually a secret.
 */
public final class EnvFormatException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Which rule a line broke. */
    public enum Reason {
        /** File larger than {@link EnvFileParser#MAX_BYTES}. */
        TOO_LARGE("the file is too large", "die Datei ist zu groß"),
        /** More than {@link EnvFileParser#MAX_LINES} lines. */
        TOO_MANY_LINES("the file has too many lines", "die Datei hat zu viele Zeilen"),
        /** Not valid UTF-8. */
        INVALID_ENCODING("the file is not valid UTF-8", "die Datei ist kein gültiges UTF-8"),
        /** Neither blank, comment nor {@code KEY=VALUE}. */
        MISSING_SEPARATOR("expected KEY=VALUE", "erwartet KEY=VALUE"),
        /** Key does not match {@code [A-Z0-9_]+} or is too long. */
        INVALID_KEY("the key must match [A-Z0-9_]+ (at most 128 characters)",
                "der Schlüssel muss [A-Z0-9_]+ entsprechen (höchstens 128 Zeichen)"),
        /** The same key appears twice. */
        DUPLICATE_KEY("the key appears more than once", "der Schlüssel kommt mehrfach vor"),
        /** A control character or line separator, which would allow multi-line values. */
        CONTROL_CHARACTER("control characters are not allowed", "Steuerzeichen sind nicht erlaubt"),
        /** Value starts or ends with whitespace, almost always a copy-and-paste mistake. */
        SURROUNDING_WHITESPACE("the value must not start or end with whitespace",
                "der Wert darf nicht mit Leerraum beginnen oder enden");

        private final String english;
        private final String german;

        Reason(String english, String german) {
            this.english = english;
            this.german = german;
        }

        /** Text in the given language. */
        public String text(Language language) {
            return language == Language.DE ? german : english;
        }
    }

    private final int line;
    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param line   1-based line, or 0 for the whole file
     * @param reason rule that was broken
     */
    public EnvFormatException(int line, Reason reason) {
        super(describe(line, Objects.requireNonNull(reason, "reason"), Language.EN));
        this.line = line;
        this.reason = reason;
    }

    /** 1-based line, or 0 for the whole file. */
    public int line() {
        return line;
    }

    /** Rule that was broken. */
    public Reason reason() {
        return reason;
    }

    /** Message in the given language. */
    public String describe(Language language) {
        return describe(line, reason, language);
    }

    private static String describe(int line, Reason reason, Language language) {
        String prefix = line > 0 ? (language == Language.DE ? "Zeile " : "line ") + line + ": " : "";
        return "vectora.env: " + prefix + reason.text(language);
    }
}
