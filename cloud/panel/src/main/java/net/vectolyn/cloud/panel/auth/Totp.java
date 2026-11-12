// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 over RFC 4226): HMAC-SHA-1, 6 digits, 30-second steps,
 * which every authenticator app understands. A code is accepted for the current step and one step
 * either side (clock drift), and never twice: {@link #verify} returns the step it matched, and the
 * caller stores it as the last used step.
 */
public final class Totp {

    /** Length of a secret in bytes (160 bits, as RFC 4226 recommends). */
    public static final int SECRET_BYTES = 20;
    /** Seconds per step. */
    public static final int STEP_SECONDS = 30;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {
    }

    /**
     * A new random secret, Base32 without padding.
     *
     * @return the secret
     */
    public static String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return base32(secret);
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads from a QR code or a link.
     *
     * @param secret  Base32 secret
     * @param account the account name
     * @param issuer  the issuer shown in the app
     * @return the URI
     */
    public static String uri(String secret, String account, String issuer) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    /**
     * Checks a code.
     *
     * @param secret   Base32 secret
     * @param code     what the user typed; spaces are ignored
     * @param epochSec now, in seconds since 1970
     * @param lastStep the last step a code was accepted for, or -1
     * @return the step the code matched, or empty if it is wrong, malformed or already used
     */
    public static OptionalLong verify(String secret, String code, long epochSec, long lastStep) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String digits = code.replace(" ", "");
        Optional<byte[]> key = decodeBase32(secret);
        if (!digits.matches("[0-9]{6}") || key.isEmpty()) {
            return OptionalLong.empty();
        }
        long now = Math.floorDiv(epochSec, STEP_SECONDS);
        int wanted = Integer.parseInt(digits);
        for (long step = now - 1; step <= now + 1; step++) {
            // Every candidate is computed, so the time does not tell which one matched.
            boolean match = code(key.get(), step) == wanted;
            if (match && step > lastStep) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The code of one step (RFC 4226 dynamic truncation).
     *
     * @param key  the secret bytes
     * @param step the time step
     * @return the six-digit code as a number
     */
    static int code(byte[] key, long step) {
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xff);
            step >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is missing", e);
        }
    }

    /**
     * Base32 (RFC 4648) without padding.
     *
     * @param data bytes
     * @return the text
     */
    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    /**
     * Decodes Base32 (case-insensitive, padding and spaces ignored).
     *
     * @param text the text
     * @return the bytes, or empty if it has other characters or is longer than 128 characters
     */
    public static Optional<byte[]> decodeBase32(String text) {
        if (text == null || text.length() > 128) {
            return Optional.empty();
        }
        String clean = text.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return Optional.empty();
        }
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (value < 0) {
                return Optional.empty();
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return Optional.of(out);
    }
}
