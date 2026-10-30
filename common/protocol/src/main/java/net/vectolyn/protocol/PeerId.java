package net.vectolyn.protocol;

/**
 * Identity a peer announces in {@code Hello}: a {@link ServiceId} for proxies and servers, a
 * {@link NodeId} for cluster nodes. On the wire it is the role code (one byte) followed by the
 * id as a string.
 */
public sealed interface PeerId permits ServiceId, NodeId {

    /** Returns the kind of peer. */
    PeerRole role();

    /** Returns the id as text. */
    String value();

    /** Writes the role code and the id. */
    default void writeWithRole(PacketWriter out) {
        out.writeByte(role().code());
        out.writeString(value(), maxLength(role()));
    }

    /**
     * Reads a peer id.
     *
     * @throws ProtocolException for an unknown role or an invalid id
     */
    static PeerId readWithRole(PacketReader in) {
        PeerRole role = PeerRole.fromCode(in.readUnsignedByte());
        return switch (role) {
            case SERVICE -> in.readValidated(ServiceId.MAX_LENGTH, ServiceId::new);
            case NODE -> in.readValidated(NodeId.MAX_LENGTH, NodeId::new);
        };
    }

    private static int maxLength(PeerRole role) {
        return switch (role) {
            case SERVICE -> ServiceId.MAX_LENGTH;
            case NODE -> NodeId.MAX_LENGTH;
        };
    }
}
