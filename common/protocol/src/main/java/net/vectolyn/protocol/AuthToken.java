package net.vectolyn.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Secret a peer presents in {@code Hello}: the per-start service token or the cluster join
 * token. {@value #MIN_LENGTH} to {@value #MAX_LENGTH} printable ASCII characters without spaces.
 *
 * <p>{@link #toString()} never shows the value, so a token cannot leak into logs through a
 * message's {@code toString}. Compare tokens with {@link #matches}, which takes the same time
 * whatever the content.
 *
 * @param value the secret
 */
public record AuthToken(String value) {

    /** Smallest accepted length. */
    public static final int MIN_LENGTH = 16;

    /** Largest accepted length. */
    public static final int MAX_LENGTH = 256;

    private static final Pattern PATTERN = Pattern.compile("[\\x21-\\x7E]+");

    /**
     * Creates a token.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rules above; the message does
     *     not contain the value
     */
    public AuthToken {
        Objects.requireNonNull(value, "token");
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("token must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("token may only contain printable ASCII characters without spaces");
        }
    }

    /** Compares two tokens in time that does not depend on where they differ. */
    public boolean matches(AuthToken other) {
        Objects.requireNonNull(other, "other");
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII),
                other.value.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads a token. */
    public static AuthToken read(PacketReader in) {
        return in.readValidated(MAX_LENGTH, AuthToken::new);
    }

    /** Writes the token as a string. */
    public void write(PacketWriter out) {
        out.writeString(value, MAX_LENGTH);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthToken token && matches(token);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value.getBytes(StandardCharsets.US_ASCII));
    }

    /** Returns {@code AuthToken[redacted]}, never the value. */
    @Override
    public String toString() {
        return "AuthToken[redacted]";
    }
}
