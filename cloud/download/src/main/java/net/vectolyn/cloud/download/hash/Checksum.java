package net.vectolyn.cloud.download.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An expected or computed checksum: algorithm plus lower-case hex value.
 *
 * @param algorithm the algorithm
 * @param hex the digest as lower-case hex, exactly {@link HashAlgorithm#hexLength()} characters
 */
public record Checksum(HashAlgorithm algorithm, String hex) {

    /**
     * Validates and normalises the value.
     *
     * @param algorithm the algorithm
     * @param hex the digest as hex (upper or lower case)
     * @throws IllegalArgumentException if the value is not hex of the right length
     */
    public Checksum {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(hex, "hex");
        hex = hex.toLowerCase(Locale.ROOT);
        if (hex.length() != algorithm.hexLength()) {
            throw new IllegalArgumentException(algorithm.id() + " must have " + algorithm.hexLength()
                    + " hex characters, got " + hex.length());
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                throw new IllegalArgumentException(algorithm.id() + " contains a non-hex character");
            }
        }
    }

    /**
     * Creates a checksum from raw digest bytes.
     *
     * @param algorithm the algorithm
     * @param digest the digest bytes
     * @return the checksum
     */
    public static Checksum of(HashAlgorithm algorithm, byte[] digest) {
        return new Checksum(algorithm, HexFormat.of().formatHex(digest));
    }

    /**
     * Parses a checksum and returns empty instead of throwing for a missing or invalid value.
     *
     * @param algorithm the algorithm
     * @param hex the hex value, may be {@code null}
     * @return the checksum, or empty
     */
    public static Optional<Checksum> parse(HashAlgorithm algorithm, String hex) {
        if (hex == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Checksum(algorithm, hex));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Picks the strongest checksum from what a source offers.
     *
     * @param candidates the offered checksums
     * @return the one with the strongest algorithm, or empty if there is none
     */
    public static Optional<Checksum> strongest(Collection<Checksum> candidates) {
        Checksum best = null;
        for (Checksum candidate : candidates) {
            if (best == null || candidate.algorithm().compareTo(best.algorithm()) > 0) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Compares with another checksum in constant time.
     *
     * @param other the other checksum, may be {@code null}
     * @return {@code true} if algorithm and value are equal
     */
    public boolean matches(Checksum other) {
        return other != null && algorithm == other.algorithm
                && MessageDigest.isEqual(hex.getBytes(StandardCharsets.US_ASCII),
                        other.hex.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return algorithm.id() + ":" + hex;
    }
}
