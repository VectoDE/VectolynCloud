package net.vectora.protocol;

import java.util.regex.Pattern;

/**
 * Name of a messaging channel, for example {@code vectora.chat}. 1 to {@value #MAX_LENGTH}
 * characters from {@code [a-z0-9_.-]}, starting with a letter, digit or {@code _}.
 *
 * <p>The first character rules out {@code .}, {@code ..} and names that look like a hidden file
 * or a command line option ({@code -x}), in case a channel name ever ends up in a path or an
 * argument list.
 *
 * @param value the name
 */
public record ChannelName(String value) {

    /** Largest length in characters. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_][a-z0-9_.-]*");

    /**
     * Creates a channel name.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above
     */
    public ChannelName {
        Names.check(value, PATTERN, MAX_LENGTH, "channel name");
    }

    /** Reads a channel name. */
    public static ChannelName read(PacketReader in) {
        return in.readValidated(MAX_LENGTH, ChannelName::new);
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
