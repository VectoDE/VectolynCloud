package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.vectolyn.cloud.config.model.DatabaseMode;
import net.vectolyn.protocol.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two (or three) homes on loopback: a head with a real CA from keytool, members that join with
 * real tokens and connect with mutual TLS 1.3. Every trust decision of the join and the session,
 * with its negative path.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterJoinTest {

    private static final NodeId HEAD = new NodeId("node-1");

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
        port = TestNode.freePort();
        head.cluster.init("127.0.0.1", port, null);
    }

    @AfterEach
    void tearDown() {
        member.close();
        head.close();
    }

    private String[] token(String node) throws Exception {
        String full = head.cluster.createToken(new NodeId(node), Duration.ofMinutes(10)).token().reveal();
        String pin = head.cluster.status().pin().orElseThrow().toString();
        return new String[] {full, pin};
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void aMemberJoinsAndGetsASession() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);

        assertEquals(ClusterService.Role.MEMBER, member.cluster.role());
        await(() -> head.connected.contains(new NodeId("node-2")));
        await(() -> member.cluster.status().link().orElseThrow() == ClusterLink.State.CONNECTED);
        ClusterService.Status status = head.cluster.status();
        assertEquals(2, status.members().size());
        assertTrue(status.members().stream().allMatch(ClusterService.MemberStatus::connected));
        // The member's certificate names it and nothing else, signed by the head's CA.
        assertEquals(status.pin(), member.cluster.status().pin());
        assertTrue(head.cluster.tokens().isEmpty(), "the token is used up");
    }

    @Test
    void aWrongCaPinStopsTheJoinBeforeTheTokenIsSent() throws Exception {
        String[] t = token("node-2");
        String wrong = "sha256:" + "ab".repeat(32);
        IOException e = assertThrows(IOException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,t[0], wrong));
        assertTrue(e.getMessage().contains("pin"), e.getMessage());
        assertEquals(1, head.cluster.tokens().size(), "the token never reached the head");
        assertEquals(ClusterService.Role.STANDALONE, member.cluster.role());
        assertFalse(Files.exists(member.paths.clusterFile()));
        assertFalse(Files.exists(member.paths.nodeKeystore()), "the key of the failed join is gone");
    }

    @Test
    void anotherHeadIdStopsTheJoinBeforeTheTokenIsSent() throws Exception {
        String[] t = token("node-2");
        // As if a member sat in the path: right CA, but not the head the join line names.
        IOException e = assertThrows(IOException.class,
                () -> member.cluster.join("127.0.0.1", port, new NodeId("node-9"), t[0], t[1]));
        assertTrue(e.getMessage().contains("head"), e.getMessage());
        assertEquals(1, head.cluster.tokens().size(), "the token never reached the head");
        assertFalse(Files.exists(member.paths.nodeKeystore()), "the key of the failed join is gone");
        member.cluster.join("127.0.0.1", port, HEAD, t[0], t[1]);
        assertEquals(ClusterService.Role.MEMBER, member.cluster.role());
    }

    @Test
    void aUsedTokenIsRefusedTheSecondTime() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        try (TestNode copy = new TestNode(dir.resolve("copy"), "node-2", "1.0.0")) {
            ClusterException e = assertThrows(ClusterException.class,
                    () -> copy.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]));
            assertTrue(e.getMessage().contains("refused the token"), e.getMessage());
        }
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        head.close();
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        head.open("1.0.0", past);
        String full = head.cluster.createToken(new NodeId("node-2"), Duration.ofHours(1)).token().reveal();
        head.open("1.0.0", Clock.systemUTC());
        head.cluster.start();
        String pin = head.cluster.status().pin().orElseThrow().toString();
        ClusterException e = assertThrows(ClusterException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,full, pin));
        assertTrue(e.getMessage().contains("expired"), e.getMessage());
    }

    @Test
    void aTokenForAnotherNodeIdIsRefusedAndUsedUp() throws Exception {
        String[] t = token("node-3");
        assertThrows(ClusterException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]));
        assertTrue(head.cluster.tokens().isEmpty(), "the right secret for the wrong node uses the token up");
    }

    @Test
    void aWrongSecretIsRefused() throws Exception {
        String[] t = token("node-2");
        String forged = t[0].substring(0, 7) + "0".repeat(32);
        assertThrows(ClusterException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,forged, t[1]));
        assertEquals(1, head.cluster.tokens().size(), "a wrong secret alone does not use the token up");
    }

    @Test
    void anotherVersionDoesNotJoinAndKeepsTheToken() throws Exception {
        String[] t = token("node-2");
        member.open("1.1.0", Clock.systemUTC());
        ClusterException e = assertThrows(ClusterException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]));
        assertTrue(e.getMessage().contains("1.0.0"), e.getMessage());
        assertEquals(1, head.cluster.tokens().size());
        member.open("1.0.0", Clock.systemUTC());
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        assertEquals(ClusterService.Role.MEMBER, member.cluster.role());
    }

    @Test
    void aMemberOnAnotherVersionGetsNoSessionUntilUpdated() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        await(() -> head.connected.contains(new NodeId("node-2")));
        member.open("2.0.0", Clock.systemUTC());
        member.cluster.start();
        await(() -> member.cluster.status().link().orElseThrow() == ClusterLink.State.REFUSED);
        assertEquals(ClusterMessages.HelloOutcome.VERSION_MISMATCH, member.cluster.status().lastRefusal().orElseThrow());
        assertEquals(List.of("1.0.0"), member.versionMismatches, "the update hook learns the head's version");
    }

    @Test
    void joiningIsRefusedWhileTheHeadRunsH2() throws Exception {
        String[] t = token("node-2");
        head.database(DatabaseMode.H2, "127.0.0.1");
        ClusterException e = assertThrows(ClusterException.class, () -> member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]));
        assertTrue(e.getMessage().contains("H2"), e.getMessage());
        assertEquals(1, head.cluster.tokens().size(), "the token stays for after the switch to MariaDB");
        ClusterException create = assertThrows(ClusterException.class,
                () -> head.cluster.createToken(new NodeId("node-3"), Duration.ofMinutes(5)));
        assertTrue(create.getMessage().contains("H2"), create.getMessage());
    }

    @Test
    void aMemberGetsNoSessionOnceTheHeadIsBackOnH2() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD, t[0], t[1]);
        await(() -> head.connected.contains(new NodeId("node-2")));
        head.database(DatabaseMode.H2, "127.0.0.1");
        // The member restarts: its new hello meets the H2 rule, not only new joins do.
        member.open("1.0.0", Clock.systemUTC());
        member.cluster.start();
        await(() -> member.cluster.status().link().orElseThrow() == ClusterLink.State.REFUSED);
        assertEquals(ClusterMessages.HelloOutcome.DATABASE_SINGLE_NODE,
                member.cluster.status().lastRefusal().orElseThrow());
    }

    @Test
    void aRemovedMemberLosesItsSessionAndCannotComeBack() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        await(() -> head.connected.contains(new NodeId("node-2")));

        head.cluster.remove(new NodeId("node-2"));

        await(() -> head.disconnected.contains(new NodeId("node-2")));
        await(() -> member.cluster.status().link().orElseThrow() == ClusterLink.State.REFUSED);
        assertEquals(ClusterMessages.HelloOutcome.REVOKED, member.cluster.status().lastRefusal().orElseThrow());
        assertEquals(1, head.cluster.status().revoked());
        String file = Files.readString(head.paths.clusterFile(), StandardCharsets.UTF_8);
        assertTrue(file.contains("revoked-at"), file);
    }

    @Test
    void theRevocationSurvivesARestartOfTheHead() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        await(() -> head.connected.contains(new NodeId("node-2")));
        head.cluster.remove(new NodeId("node-2"));
        head.open("1.0.0", Clock.systemUTC());
        head.cluster.start();
        await(() -> member.cluster.status().lastRefusal().isPresent());
        assertEquals(ClusterMessages.HelloOutcome.REVOKED, member.cluster.status().lastRefusal().orElseThrow());
    }

    @Test
    void aMemberReconnectsAfterARestartOfTheHead() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        await(() -> head.connected.contains(new NodeId("node-2")));
        head.open("1.0.0", Clock.systemUTC());
        head.connected.clear();
        head.cluster.start();
        await(() -> head.connected.contains(new NodeId("node-2")));
    }

    @Test
    void aMemberRestartsIntoItsSession() throws Exception {
        String[] t = token("node-2");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        member.open("1.0.0", Clock.systemUTC());
        member.cluster.start();
        await(() -> member.cluster.status().link().orElseThrow() == ClusterLink.State.CONNECTED);
    }

    @Test
    void aHeadCannotJoinAnotherCluster() throws Exception {
        try (TestNode other = new TestNode(dir.resolve("other"), "node-9", "1.0.0")) {
            other.cluster.init("127.0.0.1", TestNode.freePort(), null);
            String[] t = token("node-9");
            assertThrows(ClusterException.class, () -> other.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]));
        }
    }

    @Test
    void secretsStayOutOfTheFiles() throws Exception {
        String[] t = token("node-2");
        String secret = t[0].substring(7);
        String tokens = Files.readString(head.paths.joinTokens(), StandardCharsets.UTF_8);
        assertFalse(tokens.contains(secret), "only the hash of the secret is stored");
        member.cluster.join("127.0.0.1", port, HEAD,t[0], t[1]);
        // The mirror writes in the background after the join; let its first sync finish.
        await(() -> member.cluster.status().mirror().orElseThrow().lastSync().isPresent());
        for (Path home : List.of(head.home, member.home)) {
            try (var files = Files.walk(home)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    byte[] content;
                    try {
                        content = Files.readAllBytes(file);
                    } catch (NoSuchFileException e) {
                        continue; // a temporary file renamed into place meanwhile; its target is read too
                    }
                    assertFalse(new String(content, StandardCharsets.ISO_8859_1).contains(secret), file.toString());
                }
            }
        }
    }
}
