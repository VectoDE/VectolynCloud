package net.vectora.protocol;

/**
 * Name of one running proxy or server, for example {@code lobby-1}. 1 to
 * {@value #MAX_LENGTH} lower-case ASCII letters, digits, {@code _} and {@code -}, starting with a
 * letter or digit, and not a Windows device name such as {@code con} or {@code com1}. A service id
 * is usually {@code <group>-<index>}, so the limit leaves room for a {@link GroupName} of full
 * length. Service ids become file and directory names, and Windows and macOS do not tell upper
 * and lower case apart there.
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
        Names.checkIdentifier(value, MAX_LENGTH, "service id");
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
