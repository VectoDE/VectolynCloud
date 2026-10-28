package net.vectora.protocol;

/**
 * Name of a VectoraCloud node in a cluster, for example {@code node-1}. Same rules as
 * {@link ServiceId}: 1 to {@value #MAX_LENGTH} ASCII letters, digits, {@code _} and {@code -},
 * starting with a letter or digit.
 *
 * @param value the id
 */
public record NodeId(String value) implements PeerId {

    /** Largest length in characters. */
    public static final int MAX_LENGTH = 64;

    /**
     * Creates a node id.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above
     */
    public NodeId {
        Names.check(value, Names.IDENTIFIER, MAX_LENGTH, "node id");
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
