package net.vectora.cloud.node.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import net.vectora.cloud.config.secret.Secret;

/**
 * Credential of one start of one service. A new token is created for every start and handed to
 * the process in the environment variable {@value #ENV}; the bridge (or the Vectora plugin) sends
 * it back when it connects to the cloud channel. The token is never written to a file and never
 * logged: {@link #toString()} is masked, and the node's log filter replaces it in the server output.
 */
public final class ServiceToken {

    /** Environment variable that carries the token into the service. */
    public static final String ENV = "VECTORA_SERVICE_TOKEN";
    /** Random bytes per token (256 bit). */
    public static final int BYTES = 32;

    private final Secret value;

    private ServiceToken(Secret value) {
        this.value = value;
    }

    /**
     * Creates a new random token: 32 bytes from {@code random}, base64url without padding
     * (43 characters from {@code A-Z a-z 0-9 - _}).
     *
     * @param random source of randomness
     * @return the token
     */
    public static ServiceToken generate(SecureRandom random) {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return new ServiceToken(Secret.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    /**
     * Returns the token as a {@link Secret}, for the environment of the process and the log filter.
     *
     * @return the value
     */
    public Secret secret() {
        return value;
    }

    /**
     * Compares a presented token with this one in constant time.
     *
     * @param presented the token a client sent, may be null
     * @return whether it is this token
     */
    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = sha256(value.reveal());
        byte[] actual = sha256(presented);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java SE specification", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServiceToken token && Objects.equals(value, token.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return Secret.MASK;
    }
}
