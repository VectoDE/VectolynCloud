package net.vectolyn.cloud.console.prompt;

import java.util.Objects;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.console.Text;

/**
 * An answer does not fit a question. The message says what is expected, never what was typed (it
 * may be a secret). The question is asked again.
 */
public final class InvalidAnswerException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Text text;
    private final transient Object[] args;

    /** What is expected, with {@link String#format} arguments. */
    public InvalidAnswerException(Text text, Object... args) {
        super(Objects.requireNonNull(text, "text").format(Language.EN, args));
        this.text = text;
        this.args = args.clone();
    }

    /** The message in {@code language}. */
    public String message(Language language) {
        return text == null ? getMessage() : text.format(language, args);
    }
}
