package net.vectora.cloud.console;

/** Text styles of the console. ANSI codes are added only when the terminal supports them. */
public enum Style {
    /** Normal text. */
    PLAIN(""),
    /** Headings. */
    HEADING("\u001b[1m"),
    /** The prompt. */
    PROMPT("\u001b[36m"),
    /** Success. */
    SUCCESS("\u001b[32m"),
    /** Warnings. */
    WARNING("\u001b[33m"),
    /** Errors. */
    ERROR("\u001b[31m"),
    /** Less important text, such as defaults in a question. */
    MUTED("\u001b[2m");

    private static final String RESET = "\u001b[0m";

    private final String code;

    Style(String code) {
        this.code = code;
    }

    /** {@code text} in this style, or unchanged if {@code ansi} is false. */
    public String apply(String text, boolean ansi) {
        if (!ansi || code.isEmpty() || text.isEmpty()) {
            return text;
        }
        return code + text + RESET;
    }
}
