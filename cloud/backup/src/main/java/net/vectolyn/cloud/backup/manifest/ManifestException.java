package net.vectolyn.cloud.backup.manifest;

/** A manifest is broken or breaks a rule. The message names the rule, never the offending value. */
public final class ManifestException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which rule was broken
     */
    public ManifestException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message which rule was broken
     * @param cause the cause
     */
    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
