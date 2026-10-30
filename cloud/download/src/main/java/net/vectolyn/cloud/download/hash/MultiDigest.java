package net.vectolyn.cloud.download.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Computes several digests over the same bytes in one pass, so a download is read only once
 * for its SHA-256 content address and for the checksum its source publishes.
 */
public final class MultiDigest {

    private final Map<HashAlgorithm, MessageDigest> digests = new EnumMap<>(HashAlgorithm.class);

    /**
     * Creates the digests.
     *
     * @param algorithms the algorithms to compute; SHA-256 is always added
     */
    public MultiDigest(Collection<HashAlgorithm> algorithms) {
        digests.put(HashAlgorithm.SHA256, HashAlgorithm.SHA256.newDigest());
        for (HashAlgorithm algorithm : algorithms) {
            digests.computeIfAbsent(algorithm, HashAlgorithm::newDigest);
        }
    }

    /**
     * Feeds bytes into every digest.
     *
     * @param buffer the bytes
     * @param offset start in the buffer
     * @param length number of bytes
     */
    public void update(byte[] buffer, int offset, int length) {
        for (MessageDigest digest : digests.values()) {
            digest.update(buffer, offset, length);
        }
    }

    /**
     * Finishes the digests. Call only once.
     *
     * @return one checksum per algorithm
     */
    public Map<HashAlgorithm, Checksum> finish() {
        Map<HashAlgorithm, Checksum> result = new EnumMap<>(HashAlgorithm.class);
        digests.forEach((algorithm, digest) -> result.put(algorithm, Checksum.of(algorithm, digest.digest())));
        return result;
    }

    /**
     * Hashes a file.
     *
     * @param file the file
     * @param algorithms the algorithms to compute; SHA-256 is always added
     * @return one checksum per algorithm
     * @throws IOException if the file cannot be read
     */
    public static Map<HashAlgorithm, Checksum> of(Path file, Collection<HashAlgorithm> algorithms) throws IOException {
        MultiDigest multi = new MultiDigest(algorithms);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                multi.update(buffer, 0, read);
            }
        }
        return multi.finish();
    }

    /**
     * Hashes bytes in memory.
     *
     * @param data the bytes
     * @param algorithms the algorithms to compute; SHA-256 is always added
     * @return one checksum per algorithm
     */
    public static Map<HashAlgorithm, Checksum> of(byte[] data, Collection<HashAlgorithm> algorithms) {
        MultiDigest multi = new MultiDigest(algorithms);
        multi.update(data, 0, data.length);
        return multi.finish();
    }
}
