package net.vectolyn.cloud.config.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A secret value (password, token, key). It cannot end up in a log by accident:
 * {@link #toString()} returns {@code ***}, and the value is reachable only through
 * {@link #reveal()}, which makes every use visible in the code.
 *
 * <p>{@link #equals} compares in constant time for values of equal length, so comparing a token
 * from a request with a stored one does not leak how many leading characters matched.
 * {@link #hashCode()} is a constant for the same reason; secrets are not meant as hash keys.
 *
 * <p>The value is a {@link String} and therefore cannot be wiped from memory. That is accepted:
 * the secrets are written to server configs as text anyway.
 */
public final class Secret {

    /** What {@link #toString()} returns. */
    public static final String MASK = "***";

    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    /** Wraps {@code value}. */
    public static Secret of(String value) {
        return new Secret(Objects.requireNonNull(value, "value"));
    }

    /** The plain value. Every call site is a place where the secret leaves this type, so keep them few. */
    public String reveal() {
        return value;
    }

    /** Whether the value is empty (an unset entry such as {@code KEY=}). */
    public boolean isEmpty() {
        return value.isEmpty();
    }

    /** Length of the value in characters. */
    public int length() {
        return value.length();
    }

    /** Constant-time comparison of the values. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Secret secret
                && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), secret.value.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant, so the hash does not reveal anything about the value. */
    @Override
    public int hashCode() {
        return 0x5ec7e7;
    }

    /** Always {@value #MASK}. */
    @Override
    public String toString() {
        return MASK;
    }
}
