package net.vectolyn.protocol;

/** What kind of peer opens a connection to the cloud. */
public enum PeerRole {

    /** A proxy or server started by the cloud (bridge or Vectolyn plugin). */
    SERVICE(0),

    /** Another VectolynCloud node of the same cluster. */
    NODE(1);

    private final int code;

    PeerRole(int code) {
        this.code = code;
    }

    /** Returns the wire code. */
    public int code() {
        return code;
    }

    /**
     * Returns the role with the given wire code.
     *
     * @throws ProtocolException for an unknown code
     */
    public static PeerRole fromCode(int code) {
        for (PeerRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new ProtocolException("unknown peer role " + code);
    }
}
