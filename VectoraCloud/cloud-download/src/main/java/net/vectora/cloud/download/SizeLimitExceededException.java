package net.vectora.cloud.download;

/** A response body or a download is larger than the configured limit. */
public final class SizeLimitExceededException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was too large and what the limit is
     */
    public SizeLimitExceededException(String message) {
        super(message);
    }
}
