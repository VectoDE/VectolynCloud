package net.vectora.cloud.cluster;

import net.vectora.cloud.config.model.Language;

/** A cluster action that cannot be done, with the reason in English and German. */
public final class ClusterException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String german;

    /**
     * Creates the exception.
     *
     * @param english the reason in English (also {@link #getMessage()})
     * @param german  the reason in German
     */
    public ClusterException(String english, String german) {
        super(english);
        this.german = german;
    }

    /**
     * Creates the exception with a cause.
     *
     * @param english the reason in English
     * @param german  the reason in German
     * @param cause   the cause
     */
    public ClusterException(String english, String german, Throwable cause) {
        super(english, cause);
        this.german = german;
    }

    /** The reason in {@code language}. */
    public String message(Language language) {
        return language == Language.DE ? german : getMessage();
    }
}
