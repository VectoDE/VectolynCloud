package net.vectolyn.cloud.download;

/** A download API answered with JSON that does not have the expected shape. */
public final class MalformedResponseException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is missing or wrong
     */
    public MalformedResponseException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong
     * @param cause the parser error
     */
    public MalformedResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
