package net.vectora.cloud.network;

/** A request could not be sent or answered because the service has no open connection. */
public final class ServiceNotConnectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ServiceNotConnectedException(String message) {
        super(message);
    }
}
