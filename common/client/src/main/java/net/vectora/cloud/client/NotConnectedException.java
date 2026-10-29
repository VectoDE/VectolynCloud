package net.vectora.cloud.client;

/** A request could not be sent or answered because the client has no connection to the cloud. */
public final class NotConnectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public NotConnectedException(String message) {
        super(message);
    }
}
