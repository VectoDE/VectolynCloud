package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.CatalogKind;
import net.vectolyn.cloud.config.model.CatalogReference;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.NodeConfig;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TimeSpan;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.PutTask;
import net.vectolyn.cloud.config.operation.RemoveTask;
import net.vectolyn.cloud.config.operation.SetLanguage;
import net.vectolyn.cloud.config.operation.SetNetworkName;
import net.vectolyn.protocol.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A head and a member on loopback with a real CA and mutual TLS: the config mirror and the
 * template sync end to end.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterMirrorTest {

    private static final NodeId HEAD = new NodeId("node-1");

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;
    private int port;

    static GroupConfig group(String heap) {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, "Lobby-Server", ServerSoftware.PAPER, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse(heap)),
                List.of(new CatalogReference("luckperms", CatalogKind.TRANSITIONAL)), CombatSystem.MODERN);
    }

    static TaskConfig task() {
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, "lobby",
                new ScalingConfig(1, 1, 4, 50, 80, 50, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of("node-1"));
    }

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
        port = TestNode.freePort();
        head.config.apply(List.of(new PutGroup("lobby", group("768M")), new PutTask("lobby", task()),
                new SetNetworkName("HeadNetwork")));
        head.cluster.init("127.0.0.1", port, null);
    }

    @AfterEach
    void tearDown() {
        member.close();
        head.close();
    }

    private void join() throws Exception {
        String token = head.cluster.createToken(new NodeId("node-2"), Duration.ofMinutes(10)).token().reveal();
        String pin = head.cluster.status().pin().orElseThrow().toString();
        member.cluster.join("127.0.0.1", port, HEAD, token, pin);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(50);
        }
    }

    private long revision(TestNode node) {
        return node.cluster.status().mirror().orElseThrow().configRevision();
    }

    @Test
    void theMemberTakesOverNodesGroupsAndTasksButNeverCloudYml() throws Exception {
        join();
        await(() -> member.config.snapshot().groups().containsKey("lobby") && revision(member) > 0);

        // The head added the new node to nodes.yml with the address the join came from.
        NodeConfig added = head.config.snapshot().nodes().find("node-2").orElseThrow();
        assertEquals("127.0.0.1", added.host());
        await(() -> revision(member) == revision(head));
        assertEquals(head.config.snapshot().nodes(), member.config.snapshot().nodes());
        assertEquals(task(), member.config.snapshot().tasks().get("lobby"));
        for (String file : List.of("nodes.yml", "groups/lobby.yml", "tasks/lobby.yml")) {
            assertArrayEquals(Files.readAllBytes(head.home.resolve("config").resolve(file)),
                    Files.readAllBytes(member.home.resolve("config").resolve(file)), file + " has the head's bytes");
        }
        // cloud.yml stays the member's own.
        assertEquals("node-2", member.config.snapshot().cloud().nodeId());
        assertFalse(member.config.snapshot().cloud().networkName().equals("HeadNetwork"));
        assertTrue(member.config.mirrorMode());

        // A change on the head arrives by push.
        long before = revision(member);
        head.config.apply(new PutGroup("lobby", group("1G")));
        await(() -> group("1G").equals(member.config.snapshot().groups().get("lobby")));
        assertTrue(revision(member) > before);

        // So does a removal.
        head.config.apply(new RemoveTask("lobby"));
        await(() -> member.config.snapshot().tasks().isEmpty());
        assertFalse(Files.exists(member.home.resolve("config/tasks/lobby.yml")));
    }

    @Test
    void aMemberRefusesItsOwnChangesAndUndoesHandEdits() throws Exception {
        join();
        await(() -> member.config.snapshot().groups().containsKey("lobby"));

        ConfigException refused = assertThrows(ConfigException.class,
                () -> member.config.apply(new PutGroup("other", group("768M"))));
        assertEquals(ErrorCode.MANAGED_BY_HEAD, refused.errors().getFirst().code());
        assertFalse(member.config.snapshot().groups().containsKey("other"));
        member.config.apply(new SetLanguage(Language.DE));
        assertEquals(Language.DE, member.config.snapshot().cloud().language(), "cloud.yml is its own");

        Path file = member.home.resolve("config/groups/lobby.yml");
        byte[] mirrored = Files.readAllBytes(file);
        Files.writeString(file, new String(mirrored, StandardCharsets.UTF_8).replace("768M", "2G"));
        member.config.reload();
        assertArrayEquals(mirrored, Files.readAllBytes(file));
        assertEquals(group("768M"), member.config.snapshot().groups().get("lobby"));
    }

    @Test
    void aStaleRevisionIsRefused() throws Exception {
        join();
        await(() -> revision(member) > 0);
        MirrorState.writeRevision(member.paths.configApplied(), new MirrorState.Revision(1000, new byte[32]));

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> member.cluster.sync().get(30, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("stale"), e.getCause().getMessage());
        assertTrue(member.cluster.status().mirror().orElseThrow().problem().orElseThrow().contains("stale"));
        assertEquals(1000, revision(member), "nothing was taken over");
    }

    @Test
    void theRevisionSurvivesARestartOfTheHead() throws Exception {
        await(() -> revision(head) > 0);
        long first = revision(head);
        head.open("1.0.0", java.time.Clock.systemUTC());
        head.cluster.start();
        await(() -> revision(head) > 0);
        assertEquals(first, revision(head), "the same files keep their revision");
        head.config.apply(new PutGroup("lobby", group("1G")));
        await(() -> revision(head) == first + 1);
    }

    @Test
    void templatesAreMirroredWithoutSecretsAndLinks() throws Exception {
        Path lobby = head.home.resolve("local/templates/Lobby-Server/default");
        Files.createDirectories(lobby.resolve("plugins/config"));
        Files.writeString(lobby.resolve("server.properties"), "motd=hello\n");
        byte[] jar = new byte[200_000];
        for (int i = 0; i < jar.length; i++) {
            jar[i] = (byte) (i * 31 + i / 7);
        }
        Files.write(lobby.resolve("plugins/big.jar"), jar);
        Files.writeString(lobby.resolve("plugins/config/empty.yml"), "");
        Files.writeString(lobby.resolve("forwarding.secret"), "do-not-copy");
        Files.writeString(lobby.resolve("vectolyn.env"), "do-not-copy");
        Files.createDirectories(lobby.resolve("secrets"));
        Files.writeString(lobby.resolve("secrets/token.txt"), "do-not-copy");
        Path outside = Files.writeString(dir.resolve("outside.txt"), "outside");
        boolean links = tryLink(lobby.resolve("link.txt"), outside);
        Path own = member.home.resolve("local/templates/Own/default");
        Files.createDirectories(own);
        Files.writeString(own.resolve("mine.txt"), "mine");

        join();
        Path mirrored = member.home.resolve("local/templates/Lobby-Server/default");
        await(() -> Files.exists(mirrored.resolve("plugins/big.jar"))
                && member.cluster.status().mirror().orElseThrow().templates() == 1);

        assertEquals("motd=hello\n", Files.readString(mirrored.resolve("server.properties")));
        assertArrayEquals(jar, Files.readAllBytes(mirrored.resolve("plugins/big.jar")));
        assertEquals(0, Files.size(mirrored.resolve("plugins/config/empty.yml")));
        assertFalse(Files.exists(mirrored.resolve("forwarding.secret")));
        assertFalse(Files.exists(mirrored.resolve("vectolyn.env")));
        assertFalse(Files.exists(mirrored.resolve("secrets")));
        // tryLink fails the test where a link must be possible; only Windows may skip this check.
        if (links) {
            assertFalse(Files.exists(mirrored.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        }
        // A template the mirror never synced (a built-in one or the operator's own) stays.
        assertEquals("mine", Files.readString(own.resolve("mine.txt")));

        // A change, a new template and a removed one arrive with "cluster sync".
        Files.writeString(lobby.resolve("server.properties"), "motd=changed\n");
        Path proxy = head.home.resolve("local/templates/Proxy/default");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve("velocity.toml"), "bind = \"0.0.0.0:25565\"\n");
        head.cluster.sync().get(30, TimeUnit.SECONDS);
        await(() -> Files.exists(member.home.resolve("local/templates/Proxy/default/velocity.toml"))
                && read(mirrored.resolve("server.properties")).equals("motd=changed\n"));
        assertArrayEquals(jar, Files.readAllBytes(mirrored.resolve("plugins/big.jar")), "unchanged files are kept");

        TemplateSink.deleteTree(head.home.resolve("local/templates/Proxy"));
        head.cluster.sync().get(30, TimeUnit.SECONDS);
        await(() -> !Files.exists(member.home.resolve("local/templates/Proxy")));
        assertEquals(Optional.empty(), member.cluster.status().mirror().orElseThrow().problem());
        assertFalse(Files.exists(member.paths.syncWorkDir().resolve("staging/Lobby-Server")), "no staging left");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows");

    /**
     * A symbolic link, if this system lets the test make one. Only Windows may refuse (it needs
     * Developer Mode or an administrator); everywhere else a refusal fails the test, so the Linux
     * CI jobs always run the link checks.
     */
    static boolean tryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            if (!WINDOWS) {
                throw new AssertionError("this system must allow symbolic links for the link tests", e);
            }
            return false;
        }
    }

    /**
     * A symbolic link, or the test is skipped where only Windows may refuse one (see
     * {@link #tryLink}).
     */
    static void link(Path link, Path target) {
        Assumptions.assumeTrue(tryLink(link, target), "Windows refuses symbolic links without Developer Mode or an "
                + "administrator; the Linux CI jobs run this test");
    }

    /** A Windows directory junction (needs no privilege), made with {@code mklink /J}. */
    static void junction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
