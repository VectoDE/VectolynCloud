package net.vectolyn.protocol;

/**
 * Name of a VectolynCloud node in a cluster, for example {@code node-1}. Same rules as
 * {@link GroupName}: 1 to {@value #MAX_LENGTH} lower-case ASCII letters, digits, {@code _} and
 * {@code -}, starting with a letter or digit, and not a Windows device name such as {@code con}
 * or {@code com1}.
 *
 * @param value the id
 */
public record NodeId(String value) implements PeerId {

    /** Largest length in characters. */
    public static final int MAX_LENGTH = 32;

    /**
     * Creates a node id.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above
     */
    public NodeId {
        Names.checkIdentifier(value, MAX_LENGTH, "node id");
    }

    @Override
    public PeerRole role() {
        return PeerRole.NODE;
    }

    /** Returns the id itself. */
    @Override
    public String toString() {
        return value;
    }
}
