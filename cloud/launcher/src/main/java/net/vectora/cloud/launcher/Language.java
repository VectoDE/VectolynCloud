package net.vectora.cloud.launcher;

import java.util.Locale;
import java.util.Optional;

/** Language of the launcher's messages. Admins read German or English, see CONTRIBUTING.md. */
public enum Language {
    /** German. */
    DE("de"),
    /** English, also the fallback for every other language. */
    EN("en");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    /**
     * Returns the ISO 639-1 code.
     *
     * @return {@code "de"} or {@code "en"}
     */
    public String code() {
        return code;
    }

    /**
     * Parses a value of {@code --lang}.
     *
     * @param value {@code de} or {@code en}, case does not matter
     * @return the language, or empty for any other value
     */
    public static Optional<Language> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "de" -> Optional.of(DE);
            case "en" -> Optional.of(EN);
            default -> Optional.empty();
        };
    }

    /**
     * Maps the JVM's {@code user.language} to a launcher language: German stays German,
     * everything else becomes English.
     *
     * @param userLanguage value of {@code user.language}, may be {@code null}
     * @return the language
     */
    public static Language fromUserLanguage(String userLanguage) {
        return "de".equalsIgnoreCase(userLanguage) ? DE : EN;
    }

    /**
     * Finds the language before the full argument parser runs. The admin check comes before
     * argument parsing, but its message must already be in the right language. A later
     * {@code --lang} wins over an earlier one; invalid values are ignored here and reported by
     * the parser.
     *
     * @param args         raw command line arguments
     * @param userLanguage value of {@code user.language}, may be {@code null}
     * @return the language for early messages
     */
    public static Language early(String[] args, String userLanguage) {
        Language result = fromUserLanguage(userLanguage);
        for (int i = 0; i < args.length; i++) {
            Optional<Language> found = Optional.empty();
            if (args[i].equals("--lang") && i + 1 < args.length) {
                found = parse(args[i + 1]);
            } else if (args[i].startsWith("--lang=")) {
                found = parse(args[i].substring("--lang=".length()));
            }
            if (found.isPresent()) {
                result = found.get();
            }
        }
        return result;
    }
}
