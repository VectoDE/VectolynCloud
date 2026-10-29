package net.vectora.cloud.console.command;

import java.util.Objects;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.Text;

/**
 * A command cannot do what was asked, because of the input or the current state. The console
 * shows the message as an error in the configured language, without a stack trace.
 */
public final class CommandException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Text text;
    private final transient Object[] args;

    /** The message, with {@link String#format} arguments. */
    public CommandException(Text text, Object... args) {
        super(Objects.requireNonNull(text, "text").format(Language.EN, args));
        this.text = text;
        this.args = args.clone();
    }

    /** The message in {@code language}. */
    public String message(Language language) {
        return text == null ? getMessage() : text.format(language, args);
    }
}
