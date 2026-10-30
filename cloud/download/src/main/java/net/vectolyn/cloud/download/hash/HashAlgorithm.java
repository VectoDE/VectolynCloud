package net.vectolyn.cloud.download.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;

/**
 * Checksum algorithms the download sources publish, ordered from weakest to strongest.
 *
 * <p>SHA-1 is only accepted because Mojang publishes nothing else for its server jars. Whenever a
 * source offers several checksums, the strongest one is used ({@link Checksum#strongest}).
 */
public enum HashAlgorithm {

    /** SHA-1, 160 bit. Mojang only. */
    SHA1("sha1", "SHA-1", 40),
    /** SHA-256. Also the content address of the cache. */
    SHA256("sha256", "SHA-256", 64),
    /** SHA-512. Modrinth. */
    SHA512("sha512", "SHA-512", 128);

    private final String id;
    private final String jcaName;
    private final int hexLength;

    HashAlgorithm(String id, String jcaName, int hexLength) {
        this.id = id;
        this.jcaName = jcaName;
        this.hexLength = hexLength;
    }

    /**
     * Returns the name used in the lockfile and in the APIs, for example {@code sha256}.
     *
     * @return the lower-case identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the number of hex characters of a digest of this algorithm.
     *
     * @return the hex length
     */
    public int hexLength() {
        return hexLength;
    }

    /**
     * Creates a fresh digest. Every JDK ships these three algorithms.
     *
     * @return a new message digest
     */
    public MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(jcaName);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK without " + jcaName, e);
        }
    }

    /**
     * Looks up an algorithm by its identifier, ignoring case, dashes and a trailing {@code sum}
     * (MariaDB writes {@code sha256sum}).
     *
     * @param name the identifier, may be {@code null}
     * @return the algorithm, or empty if unknown
     */
    public static Optional<HashAlgorithm> fromId(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.toLowerCase(Locale.ROOT).replace("-", "");
        if (key.endsWith("sum")) {
            key = key.substring(0, key.length() - 3);
        }
        for (HashAlgorithm algorithm : values()) {
            if (algorithm.id.equals(key)) {
                return Optional.of(algorithm);
            }
        }
        return Optional.empty();
    }
}
