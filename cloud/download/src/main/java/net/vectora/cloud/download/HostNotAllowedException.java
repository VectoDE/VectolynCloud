package net.vectora.cloud.download;

/** A request or a redirect pointed to a host outside the allowlist, or to a non-HTTPS URL. */
public final class HostNotAllowedException extends DownloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which URL was refused and why
     */
    public HostNotAllowedException(String message) {
        super(message);
    }
}
