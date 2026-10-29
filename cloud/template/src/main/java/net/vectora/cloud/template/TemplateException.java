package net.vectora.cloud.template;

import java.util.Objects;

/**
 * A template could not be rendered: an unknown or malformed placeholder, a file that is not valid
 * UTF-8, a path that leaves the runtime folder, a missing or invalid secret.
 *
 * <p>The message starts with the file (relative to the template or runtime folder) and, where it
 * applies, the line, as in {@code velocity.toml:93: ...}. It never contains a placeholder value,
 * because values include secrets.
 */
public final class TemplateException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Line number used when an error is not tied to a line. */
    public static final int NO_LINE = 0;

    /** File the error belongs to. */
    private final String file;
    /** One-based line number, or {@link #NO_LINE}. */
    private final int line;

    /**
     * Creates an error for a line of a file.
     *
     * @param file file the error belongs to, relative to the template or runtime folder
     * @param line one-based line number, or {@link #NO_LINE}
     * @param reason what is wrong, without any placeholder value
     */
    public TemplateException(String file, int line, String reason) {
        super(location(file, line) + ": " + reason);
        this.file = file;
        this.line = line;
    }

    /**
     * Creates an error for a file as a whole.
     *
     * @param file file the error belongs to, relative to the template or runtime folder
     * @param reason what is wrong, without any placeholder value
     */
    public TemplateException(String file, String reason) {
        this(file, NO_LINE, reason);
    }

    private static String location(String file, int line) {
        Objects.requireNonNull(file, "file");
        return line > 0 ? file + ":" + line : file;
    }

    /**
     * Returns the file the error belongs to.
     *
     * @return the file, relative to the template or runtime folder
     */
    public String file() {
        return file;
    }

    /**
     * Returns the line the error belongs to.
     *
     * @return the one-based line number, or {@link #NO_LINE}
     */
    public int line() {
        return line;
    }
}
