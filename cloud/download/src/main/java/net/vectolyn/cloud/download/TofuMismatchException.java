package net.vectolyn.cloud.download;

/**
 * An artifact without a published checksum (trust on first use) no longer matches the hash that
 * was pinned in the lockfile on the first download.
 */
public final class TofuMismatchException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which artifact, pinned and actual hash
     */
    public TofuMismatchException(String message) {
        super(message);
    }
}
