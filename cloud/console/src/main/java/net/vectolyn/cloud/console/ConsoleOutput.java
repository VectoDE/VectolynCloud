package net.vectolyn.cloud.console;

import net.vectolyn.cloud.config.model.Language;

/**
 * Where commands and assistants write. Lines appear above the prompt, so the input being typed
 * stays intact. Colors are used only on a terminal that understands ANSI codes; elsewhere the
 * text is plain.
 */
public interface ConsoleOutput {

    /** The language of {@code cloud.yml}, read again for every line. */
    Language language();

    /** A plain line. */
    void println(String text);

    /** A heading. */
    void heading(String text);

    /** Something worked. */
    void success(String text);

    /** Something needs attention. */
    void warning(String text);

    /** Something failed. */
    void error(String text);

    /** A plain line in the configured language. */
    default void println(Text text, Object... args) {
        println(text.format(language(), args));
    }

    /** A success line in the configured language. */
    default void success(Text text, Object... args) {
        success(text.format(language(), args));
    }

    /** A warning in the configured language. */
    default void warning(Text text, Object... args) {
        warning(text.format(language(), args));
    }

    /** An error in the configured language. */
    default void error(Text text, Object... args) {
        error(text.format(language(), args));
    }
}
