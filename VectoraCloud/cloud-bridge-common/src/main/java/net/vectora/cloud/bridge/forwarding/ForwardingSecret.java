package net.vectora.cloud.bridge.forwarding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The forwarding secret of the legacy proxy, which Velocity sends to the backends as the
 * {@code bungeeguard-token} property. Only its SHA-256 digest is kept, {@code toString()} is
 * redacted, and {@link #matches} compares digests with {@link MessageDigest#isEqual}, so the time
 * depends neither on the content nor on the length of the candidate.
 */
public final class ForwardingSecret {

    /** Environment variable the cloud passes the secret in. Same name as the template placeholder. */
    public static final String ENV = "VECTORA_LEGACY_FORWARDING_SECRET";

    /** The secret rule of cloud-template (docs/cloud/template.md). */
    private static final Pattern RULE = Pattern.compile("[A-Za-z0-9._~+/=-]{16,1024}");

    private final byte[] digest;

    private ForwardingSecret(byte[] digest) {
        this.digest = digest;
    }

    /**
     * Creates the secret.
     *
     * @throws IllegalArgumentException if it breaks the secret rule (16 to 1024 characters from
     *     {@code A-Z a-z 0-9 . _ ~ + / = -}); the message never contains the value
     */
    public static ForwardingSecret of(String secret) {
        Objects.requireNonNull(secret, "secret");
        if (!RULE.matcher(secret).matches()) {
            throw new IllegalArgumentException(ENV + " must have 16 to 1024 characters from A-Z a-z 0-9 . _ ~ + / = -");
        }
        return new ForwardingSecret(sha256(secret));
    }

    /** Whether {@code candidate} is the secret, in time independent of where or whether it differs. */
    public boolean matches(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return MessageDigest.isEqual(digest, sha256(candidate));
    }

    @Override
    public String toString() {
        return "ForwardingSecret[redacted]";
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
