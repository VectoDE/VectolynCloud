package net.vectora.protocol;

/** Constants that both ends of the cloud channel share. */
public final class Protocol {

    /**
     * Protocol version of this build. Peers must have the same major version; within a major
     * version the connection uses the lower minor version (see {@link ProtocolVersion}).
     */
    public static final ProtocolVersion PROTOCOL_VERSION = new ProtocolVersion(1, 0);

    /**
     * Largest frame body in bytes, 1 MiB: version, message id, request id and payload together,
     * without the length prefix in front of it.
     */
    public static final int MAX_FRAME_SIZE = 1 << 20;

    private Protocol() {
    }
}
