package net.vectora.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.vectora.protocol.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterConfigTest {

    private static final String FP = "ab".repeat(32);
    private static final String FP2 = "cd".repeat(32);

    @TempDir
    Path dir;

    private static ClusterConfig cluster() {
        return new ClusterConfig(1, new ClusterConfig.Head("node-1", "10.0.0.1", 7011, null),
                List.of(new ClusterConfig.Member("node-1", FP, "2026-10-30T12:00:00Z")), List.of());
    }

    @Test
    void theFileRoundTrips() throws Exception {
        Path file = dir.resolve("config").resolve("cluster.yml");
        ClusterConfig written = cluster().withMember(new ClusterConfig.Member("node-2", FP2, "2026-10-30T12:01:00Z"));
        ClusterFile.write(file, written);
        assertEquals(Optional.of(written), ClusterFile.read(file));
        assertEquals(Optional.empty(), ClusterFile.read(dir.resolve("missing.yml")));
    }

    @Test
    void revokingMovesTheMemberToTheList() {
        ClusterConfig two = cluster().withMember(new ClusterConfig.Member("node-2", FP2, "2026-10-30T12:01:00Z"));
        ClusterConfig after = two.withRevoked(two.member(new NodeId("node-2")).orElseThrow(), Instant.EPOCH);
        assertTrue(after.member(new NodeId("node-2")).isEmpty());
        assertTrue(after.isRevoked(FP2));
        assertFalse(after.isRevoked(FP));
        assertTrue(after.problems().isEmpty(), after.problems().toString());
    }

    @Test
    void revokedCertificatesThatHaveExpiredAnywayLeaveTheList() {
        ClusterConfig three = cluster().withMember(new ClusterConfig.Member("node-2", FP2, "2026-10-30T12:01:00Z"))
                .withMember(new ClusterConfig.Member("node-3", "ef".repeat(32), "2026-10-30T12:02:00Z"));
        Instant first = Instant.parse("2026-10-30T13:00:00Z");
        ClusterConfig one = three.withRevoked(three.member(new NodeId("node-2")).orElseThrow(), first);
        Instant soon = first.plus(java.time.Duration.ofDays(10));
        ClusterConfig kept = one.withRevoked(one.member(new NodeId("node-3")).orElseThrow(), soon);
        assertTrue(kept.isRevoked(FP2), "still within the life of its certificate");
        ClusterConfig late = one.withRevoked(one.member(new NodeId("node-3")).orElseThrow(),
                first.plus(ClusterCa.NODE_VALIDITY).plusSeconds(1));
        assertFalse(late.isRevoked(FP2), "its certificate has expired, the entry is gone");
        assertTrue(late.isRevoked("ef".repeat(32)));
    }

    @Test
    void localDatabaseHostsAreRecognised() {
        for (String local : List.of("localhost", "LOCALHOST.", "db.localhost", "ip6-localhost", "127.0.0.1", "127.1",
                "127.255.0.9", "::1", "[::1]", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "0.0.0.0", "::", " ")) {
            assertTrue(ClusterService.isLocalHost(local), local);
        }
        for (String remote : List.of("10.0.0.5", "db.example.org", "fd00::5", "[fd00::5]", "cafe", "mariadb")) {
            assertFalse(ClusterService.isLocalHost(remote), remote);
        }
    }

    @Test
    void invalidContentIsRefused() {
        assertThrows(IOException.class, () -> ClusterFile.parse("schema-version: 1\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: Node, host: h, port: 7011}\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: node-1, host: 'a b', port: 7011}\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: node-1, host: h, port: 0}\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: node-1, host: h, port: 7011}\n"
                + "members: [{id: node-1, fingerprint: xyz, joined-at: '2026-10-30T12:00:00Z'}]\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: node-1, host: h, port: 7011}\n"
                + "members: [{id: node-2, fingerprint: " + FP + ", joined-at: '2026-10-30T12:00:00Z'}]\n"
                + "revoked: [{id: node-2, fingerprint: " + FP + ", revoked-at: '2026-10-30T12:00:00Z'}]\n")
                .getBytes(StandardCharsets.UTF_8)), "a member with a revoked certificate");
        assertThrows(IOException.class, () -> ClusterFile.parse(("schema-version: 1\nhead: {id: node-1, host: h, port: 7011}\n"
                + "extra: 1\n").getBytes(StandardCharsets.UTF_8)), "unknown field");
    }

    @Test
    void pinsParseAndCompare() {
        String hex = "0f".repeat(32);
        assertEquals("sha256:" + hex, CaPin.parse("sha256:" + hex).orElseThrow().toString());
        assertEquals(CaPin.parse("sha256:" + hex), CaPin.parse("SHA256:" + hex.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(CaPin.parse("sha256:" + "0f".repeat(31)).isEmpty());
        assertTrue(CaPin.parse("md5:" + hex).isEmpty());
        assertTrue(CaPin.parse(null).isEmpty());
    }
}
