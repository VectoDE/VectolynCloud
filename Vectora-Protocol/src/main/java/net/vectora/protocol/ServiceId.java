package net.vectora.protocol;

/**
 * Name of one running proxy or server, for example {@code Lobby-1}. 1 to {@value #MAX_LENGTH}
 * ASCII letters, digits, {@code _} and {@code -}, starting with a letter or digit.
 *
 * @param value the id
 */
public record ServiceId(String value) implements PeerId {

    /** Largest length in characters (and bytes, since only ASCII is allowed). */
    public static final int MAX_LENGTH = 64;

    /**
     * Creates a service id.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above
     */
    public ServiceId {
        Names.check(value, Names.IDENTIFIER, MAX_LENGTH, "service id");
    }

    @Override
    public PeerRole role() {
        return PeerRole.SERVICE;
    }

    /** Reads a service id. */
    public static ServiceId read(PacketReader in) {
        return in.readValidated(MAX_LENGTH, ServiceId::new);
    }

    /** Writes the id as a string, without a role byte. */
    public void write(PacketWriter out) {
        out.writeString(value, MAX_LENGTH);
    }

    /** Returns the id itself. */
    @Override
    public String toString() {
        return value;
    }
}
