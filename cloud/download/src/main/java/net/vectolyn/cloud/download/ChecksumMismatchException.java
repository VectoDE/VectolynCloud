package net.vectolyn.cloud.download;

/** The downloaded bytes do not match the expected checksum. The partial file has been deleted. */
public final class ChecksumMismatchException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which artifact, which algorithm, expected and actual value
     */
    public ChecksumMismatchException(String message) {
        super(message);
    }
}
