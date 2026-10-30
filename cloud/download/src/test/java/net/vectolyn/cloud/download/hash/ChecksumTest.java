package net.vectolyn.cloud.download.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChecksumTest {

    @Test
    void knownDigests() {
        Map<HashAlgorithm, Checksum> digests = MultiDigest.of("abc".getBytes(StandardCharsets.US_ASCII),
                Set.of(HashAlgorithm.SHA1, HashAlgorithm.SHA512));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digests.get(HashAlgorithm.SHA1).hex());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digests.get(HashAlgorithm.SHA256).hex());
        assertTrue(digests.get(HashAlgorithm.SHA512).hex().startsWith("ddaf35a193617aba"));
    }

    @Test
    void validatesHex() {
        assertThrows(IllegalArgumentException.class, () -> new Checksum(HashAlgorithm.SHA256, "abc"));
        assertThrows(IllegalArgumentException.class, () -> new Checksum(HashAlgorithm.SHA1, "g".repeat(40)));
        assertEquals("ab".repeat(20), new Checksum(HashAlgorithm.SHA1, "AB".repeat(20)).hex());
        assertEquals(Optional.empty(), Checksum.parse(HashAlgorithm.SHA1, "zz"));
    }

    @Test
    void strongestWins() {
        Checksum sha1 = new Checksum(HashAlgorithm.SHA1, "a".repeat(40));
        Checksum sha256 = new Checksum(HashAlgorithm.SHA256, "b".repeat(64));
        Checksum sha512 = new Checksum(HashAlgorithm.SHA512, "c".repeat(128));
        assertEquals(sha512, Checksum.strongest(List.of(sha1, sha512, sha256)).orElseThrow());
        assertEquals(sha256, Checksum.strongest(List.of(sha256, sha1)).orElseThrow());
        assertTrue(Checksum.strongest(List.of()).isEmpty());
    }

    @Test
    void matchesNeedsSameAlgorithmAndValue() {
        Checksum a = new Checksum(HashAlgorithm.SHA256, "b".repeat(64));
        assertTrue(a.matches(new Checksum(HashAlgorithm.SHA256, "B".repeat(64))));
        assertFalse(a.matches(new Checksum(HashAlgorithm.SHA256, "c".repeat(64))));
        assertFalse(a.matches(null));
    }

    @Test
    void algorithmNamesFromApis() {
        assertEquals(Optional.of(HashAlgorithm.SHA256), HashAlgorithm.fromId("sha256sum"));
        assertEquals(Optional.of(HashAlgorithm.SHA512), HashAlgorithm.fromId("SHA-512"));
        assertEquals(Optional.empty(), HashAlgorithm.fromId("md5sum"));
    }
}
