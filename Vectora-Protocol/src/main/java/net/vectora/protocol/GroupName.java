package net.vectora.protocol;

/**
 * Name of a server group, for example {@code Lobby} or {@code BedWars}. 1 to
 * {@value #MAX_LENGTH} ASCII letters, digits, {@code _} and {@code -}, starting with a letter or
 * digit.
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
        Names.check(value, Names.IDENTIFIER, MAX_LENGTH, "group name");
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
