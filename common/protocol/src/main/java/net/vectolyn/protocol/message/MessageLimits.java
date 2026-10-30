package net.vectolyn.protocol.message;

import java.util.Objects;
import java.util.regex.Pattern;

/** Upper limits of the message fields, and the checks the message records share. */
public final class MessageLimits {

    /** Largest payload of {@link ChannelMessage}, {@link Request} and {@link Response}: 64 KiB. */
    public static final int MAX_PAYLOAD_SIZE = 64 * 1024;

    /** Largest player name, enough for Java names (16) plus a Floodgate prefix and Bedrock gamertags. */
    public static final int MAX_PLAYER_NAME_LENGTH = 32;

    /** Largest host name or IP literal, the DNS limit. */
    public static final int MAX_HOST_LENGTH = 253;

    /** Largest player count in {@link ServiceStatus}. */
    public static final int MAX_PLAYERS = 1_000_000;

    /** Largest ticks per second in {@link ServiceStatus}. */
    public static final double MAX_TPS = 1_000.0;

    /** Largest milliseconds per tick in {@link ServiceStatus}: one minute. */
    public static final double MAX_MSPT = 60_000.0;

    /** Printable ASCII; no space at the start or end (Floodgate may keep inner spaces). */
    private static final Pattern PLAYER_NAME = Pattern.compile("[\\x21-\\x7E](?:[\\x20-\\x7E]*[\\x21-\\x7E])?");

    /** One DNS label: 1 to 63 letters, digits or hyphens, no hyphen at either end. */
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";

    /**
     * Host name (labels separated by single dots, no trailing dot; this covers IPv4 literals)
     * or an IPv6 literal without brackets and zone id: two to eight groups of up to four hex
     * digits separated by colons, optionally ending in an IPv4 address, at most 45 characters.
     */
    private static final Pattern HOST = Pattern.compile(
            LABEL + "(?:\\." + LABEL + ")*"
            + "|(?=.{2,45}$)(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{0,4}|[0-9]{1,3}(?:\\.[0-9]{1,3}){3})");

    private MessageLimits() {
    }

    static String checkPlayerName(String name) {
        return checkText(name, PLAYER_NAME, MAX_PLAYER_NAME_LENGTH, "player name");
    }

    static String checkHost(String host) {
        return checkText(host, HOST, MAX_HOST_LENGTH, "host");
    }

    static byte[] checkPayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload has " + payload.length + " bytes, limit " + MAX_PAYLOAD_SIZE);
        }
        return payload.clone();
    }

    static int checkCount(int value, String what) {
        if (value < 0 || value > MAX_PLAYERS) {
            throw new IllegalArgumentException(what + " must be 0 to " + MAX_PLAYERS + ", got " + value);
        }
        return value;
    }

    static double checkRange(double value, double max, String what) {
        // Written so that NaN fails as well.
        if (!(value >= 0.0 && value <= max)) {
            throw new IllegalArgumentException(what + " must be 0 to " + max + ", got " + value);
        }
        return value;
    }

    static long checkNotNegative(long value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException(what + " must not be negative, got " + value);
        }
        return value;
    }

    private static String checkText(String value, Pattern pattern, int maxLength, String what) {
        Objects.requireNonNull(value, what);
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(what + " must be 1 to " + maxLength + " characters long");
        }
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " contains characters outside " + pattern.pattern());
        }
        return value;
    }
}
