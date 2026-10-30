package net.vectolyn.protocol;

/**
 * Name of a server group, for example {@code lobby} or {@code bedwars}. 1 to
 * {@value #MAX_LENGTH} lower-case ASCII letters, digits, {@code _} and {@code -}, starting with a
 * letter or digit, and not a Windows device name such as {@code con} or {@code com1}. Group names
 * become file and directory names, and Windows and macOS do not tell upper and lower case apart
 * there.
 *
 * @param value the name
 */
public record GroupName(String value) {

    /** Largest length in characters. */
    public static final int MAX_LENGTH = 32;

    /**
     * Creates a group name.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above
     */
    public GroupName {
        Names.checkIdentifier(value, MAX_LENGTH, "group name");
    }

    /** Reads a group name. */
    public static GroupName read(PacketReader in) {
        return in.readValidated(MAX_LENGTH, GroupName::new);
    }

    /** Writes the name as a string. */
    public void write(PacketWriter out) {
        out.writeString(value, MAX_LENGTH);
    }

    /** Returns the name itself. */
    @Override
    public String toString() {
        return value;
    }
}
