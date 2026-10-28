package net.vectora.cloud.console;

import java.util.Locale;
import java.util.Objects;
import net.vectora.cloud.config.model.Language;

/**
 * A text in English and German. The console shows the one that matches {@code language} in
 * {@code cloud.yml}. Templates use {@link String#format} placeholders ({@code %1$s}, ...).
 *
 * @param english English text
 * @param german  German text
 */
public record Text(String english, String german) {

    /** Both texts are required. */
    public Text {
        Objects.requireNonNull(english, "english");
        Objects.requireNonNull(german, "german");
    }

    /** Shorthand for {@code new Text(english, german)}. */
    public static Text of(String english, String german) {
        return new Text(english, german);
    }

    /** The text in {@code language}. */
    public String get(Language language) {
        return language == Language.DE ? german : english;
    }

    /**
     * The text in {@code language} with the arguments filled in. Without arguments the text is
     * returned as it is, so a literal {@code %} needs no escaping then.
     */
    public String format(Language language, Object... args) {
        if (args.length == 0) {
            return get(language);
        }
        return String.format(Locale.ROOT, get(language), args);
    }
}
