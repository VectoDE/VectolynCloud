package net.vectora.protocol;

/**
 * Thrown when a frame comes from a peer with another major protocol version. The receiver may
 * still answer with a {@code Disconnect} in its own version before it closes the connection.
 */
public final class IncompatibleVersionException extends ProtocolException {

    private static final long serialVersionUID = 1L;

    private final int remoteMajor;
    private final int remoteMinor;

    /** Creates the exception for a frame that carried {@code remoteVersion}. */
    public IncompatibleVersionException(ProtocolVersion remoteVersion) {
        super("peer speaks protocol " + remoteVersion + ", this side " + Protocol.PROTOCOL_VERSION);
        this.remoteMajor = remoteVersion.major();
        this.remoteMinor = remoteVersion.minor();
    }

    /** Returns the version the remote peer sent. */
    public ProtocolVersion remoteVersion() {
        return new ProtocolVersion(remoteMajor, remoteMinor);
    }
}
