// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Password hashes of panel accounts: PBKDF2 with HMAC-SHA-256 from the JDK, 600,000 iterations
 * (the OWASP Password Storage Cheat Sheet value for PBKDF2-HMAC-SHA256), a random 16-byte salt per
 * password and a 32-byte key. Stored as {@code pbkdf2-sha256$<iterations>$<salt>$<key>} with
 * Base64 (no padding). Comparison runs in constant time.
 *
 * <p>Password rules ({@link #problem}): 12 to 256 characters, not the account name. No other
 * composition rules, as NIST SP 800-63B recommends.
 */
public final class PasswordHash {

    /** Iterations of new hashes. */
    public static final int ITERATIONS = 600_000;
    /** Shortest password. */
    public static final int MIN_LENGTH = 12;
    /** Longest password. */
    public static final int MAX_LENGTH = 256;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;
    /** Fewest and most iterations a stored hash may have (protects against a manipulated file). */
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 10_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** A hash compared when the account does not exist, so the time does not tell. */
    private static final String DUMMY = hash("not a real password, only for timing".toCharArray(), new byte[SALT_BYTES],
            ITERATIONS);

    private PasswordHash() {
    }

    /** A parsed hash. */
    // Salt and key go to the key derivation and MessageDigest.isEqual as they are.
    @SuppressWarnings("ArrayRecordComponent")
    record Parsed(int iterations, byte[] salt, byte[] key) {
    }

    /**
     * Hashes a new password.
     *
     * @param password the password
     * @return the stored form
     */
    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hash(password, salt, ITERATIONS);
    }

    private static String hash(char[] password, byte[] salt, int iterations) {
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(derive(password, salt, iterations));
    }

    /**
     * Checks a password against a stored hash. A missing or unreadable hash compares against a
     * dummy hash and fails, in about the same time.
     *
     * @param password the password typed
     * @param stored   the stored hash, or null when the account does not exist
     * @return whether it matches
     */
    public static boolean verify(char[] password, @Nullable String stored) {
        Optional<Parsed> parsed = parse(stored);
        Parsed target = parsed.orElseGet(() -> parse(DUMMY).orElseThrow());
        byte[] key = derive(password, target.salt(), target.iterations());
        return MessageDigest.isEqual(key, target.key()) && parsed.isPresent();
    }

    /**
     * Whether a stored hash uses fewer iterations than new hashes and should be replaced at the next
     * sign-in.
     *
     * @param stored the stored hash
     * @return true if it is weaker than {@link #ITERATIONS}
     */
    public static boolean outdated(String stored) {
        return parse(stored).map(p -> p.iterations() < ITERATIONS).orElse(true);
    }

    /**
     * Reads a stored hash.
     *
     * @param stored the text
     * @return the parts, or empty if it is not a hash of this format
     */
    static Optional<Parsed> parse(@Nullable String stored) {
        if (stored == null || stored.length() > 200) {
            return Optional.empty();
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals(PREFIX) || !parts[1].matches("[0-9]{6,8}")) {
            return Optional.empty();
        }
        int iterations = Integer.parseInt(parts[1]);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            return Optional.empty();
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] key = Base64.getDecoder().decode(parts[3]);
            if (salt.length != SALT_BYTES || key.length != KEY_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new Parsed(iterations, salt, key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Why a new password is not allowed.
     *
     * @param password the password
     * @param account  the account name
     * @return a code ({@code password-too-short}, {@code password-too-long}, {@code password-is-name}),
     *         or empty if it is allowed
     */
    public static Optional<String> problem(@Nullable String password, String account) {
        if (password == null) {
            return Optional.of("password-too-short");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return Optional.of("password-too-short");
        }
        if (length > MAX_LENGTH) {
            return Optional.of("password-too-long");
        }
        if (account != null && password.equalsIgnoreCase(account)) {
            return Optional.of("password-is-name");
        }
        return Optional.empty();
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is missing", e);
        } finally {
            spec.clearPassword();
        }
    }
}
