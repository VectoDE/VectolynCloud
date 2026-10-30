package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import net.vectolyn.cloud.cluster.MirrorMessages.FileEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateEntry;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateList;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The member's rules against a head that sends what it must not: crafted answers on an in-memory
 * session. Every refusal leaves the member's files as they were.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class MirrorRulesTest {

    @TempDir
    Path dir;

    private TemplateSink sink() {
        return new TemplateSink(dir.resolve("local/templates"), dir.resolve("local/.cluster-sync"),
                dir.resolve("state/cluster/templates"));
    }

    private static byte[] sha(String text) {
        return MirrorMessages.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A manifest page written by hand, so it can carry what the records refuse. */
    private static byte[] rawManifest(byte[] digest, String path, long size, byte[] sha) {
        return new PacketWriter().writeVarLong(0).writeByteArray(digest, 32).writeVarInt(1).writeVarInt(0)
                .writeVarInt(1).writeString(path, MirrorPaths.MAX_PATH_BYTES).writeVarLong(size).writeByteArray(sha, 32)
                .toByteArray();
    }

    /** A head that offers one template "Lobby" with the given manifest and chunk answers. */
    private static ClusterHandlers head(byte[] digest, ClusterHandlers.RequestHandler manifest,
                                        ClusterHandlers.RequestHandler chunk) {
        return head(digest, 1, 5, manifest, chunk);
    }

    /** The same, with the file count and size the list gives for "Lobby". */
    private static ClusterHandlers head(byte[] digest, int files, long bytes, ClusterHandlers.RequestHandler manifest,
                                        ClusterHandlers.RequestHandler chunk) {
        return ClusterHandlers.builder()
                .request(MirrorMessages.TEMPLATE_LIST, (peer, payload) ->
                        new TemplateList(List.of(new TemplateEntry("Lobby", digest, files, bytes))).write())
                .request(MirrorMessages.TEMPLATE_MANIFEST, manifest)
                .request(MirrorMessages.TEMPLATE_CHUNK, chunk)
                .build();
    }

    @Test
    void unsafePathsAreRefused() {
        for (String path : List.of("../evil", "a/../../evil", "/etc/passwd", "a\\b", "C:/x", "a/./b", "a//b", "",
                "CON", "nul.txt", "a/LPT1", "trailing.", "trailing ", "tab\there", "nul\0byte", "a|b", "x".repeat(121),
                "a/".repeat(16) + "b", "ü".repeat(121), "bidi‮txt.exe", "zero​width", "isolate⁦x",
                "c1\u009Bcontrol", "del\u007f", "privateuse", "nbsp x", "line break", "bom﻿",
                "broken\uD800surrogate")) {
            assertFalse(MirrorPaths.isSafePath(path), path);
        }
        for (String path : List.of("server.properties", "plugins/Lux/config.yml", "world/region/r.0.0.mca",
                "a b/c-d_e.f", "ümlaut.txt", "a/".repeat(15) + "b", "日本語.yml", "emoji😀.txt")) {
            assertTrue(MirrorPaths.isSafePath(path), path);
        }
        assertFalse(MirrorPaths.isTemplateName(".hidden"));
        assertFalse(MirrorPaths.isTemplateName("Com1"));
        assertTrue(MirrorPaths.isTemplateName("Lobby-Server"));
    }

    @Test
    void onlyNodesGroupsAndTasksAreMirroredConfig() {
        for (String path : List.of("nodes.yml", "groups/lobby.yml", "tasks/bed-wars_1.yml")) {
            assertTrue(MirrorPaths.isMirroredConfig(path), path);
        }
        for (String path : List.of("cloud.yml", "monitor.yml", "backup.yml", "software.lock.yml", "cluster.yml",
                "secrets/vectolyn.env", "groups/Lobby.yml", "groups/../cloud.yml", "groups/a/b.yml", "tasks/x.yaml")) {
            assertFalse(MirrorPaths.isMirroredConfig(path), path);
        }
        assertEquals(List.of("nodes.yml"), List.copyOf(ConfigMirrorSource.render(ConfigSnapshot.defaults()).keySet()),
                "the head never renders cloud.yml");
    }

    @Test
    void aManifestWithATraversalPathIsRefused() throws Exception {
        byte[] digest = new byte[32];
        for (String path : List.of("../escape.txt", "/abs.txt", "a\\b.txt")) {
            ClusterHandlers head = head(digest, (peer, payload) -> rawManifest(digest, path, 5, sha("hello")),
                    (peer, payload) -> "hello".getBytes(StandardCharsets.UTF_8));
            try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
                MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
                assertTrue(e.getMessage().contains("invalid manifest"), e.getMessage());
            }
        }
        assertFalse(Files.exists(dir.resolve("escape.txt")));
        assertFalse(Files.exists(dir.resolve("local/templates/Lobby")));
    }

    @Test
    void aWrongSha256LeavesTheOldTemplateAndNoStaging() throws Exception {
        Path old = Files.createDirectories(dir.resolve("local/templates/Lobby"));
        Files.writeString(old.resolve("a.txt"), "old");
        List<FileEntry> entries = List.of(new FileEntry("a.txt", 5, sha("hello")));
        byte[] digest = MirrorMessages.digest(entries);
        ClusterHandlers head = head(digest, (peer, payload) -> new Manifest(0, digest, 1, 0, entries).write(),
                (peer, payload) -> "HELLO".getBytes(StandardCharsets.UTF_8));
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
            MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
            assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        }
        assertEquals("old", Files.readString(old.resolve("a.txt")));
        assertFalse(Files.exists(dir.resolve("local/.cluster-sync/staging/Lobby")));
    }

    @Test
    void aChunkOfTheWrongSizeIsRefused() throws Exception {
        List<FileEntry> entries = List.of(new FileEntry("a.txt", 5, sha("hello")));
        byte[] digest = MirrorMessages.digest(entries);
        ClusterHandlers head = head(digest, (peer, payload) -> new Manifest(0, digest, 1, 0, entries).write(),
                (peer, payload) -> "hello, and more".getBytes(StandardCharsets.UTF_8));
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
            MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
            assertTrue(e.getMessage().contains("wrong size"), e.getMessage());
        }
        assertFalse(Files.exists(dir.resolve("local/templates/Lobby")));
    }

    @Test
    void limitsOfTemplatesAreChecked() throws Exception {
        // one file above the size limit
        List<FileEntry> big = List.of(new FileEntry("big.bin", MirrorPaths.MAX_TEMPLATE_FILE_BYTES + 1, sha("x")));
        byte[] bigDigest = MirrorMessages.digest(big);
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(bigDigest,
                (peer, payload) -> new Manifest(0, bigDigest, 1, 0, big).write(), (peer, payload) -> new byte[0]))) {
            assertTrue(assertThrows(MirrorException.class, () -> sink().sync(pair.member)).getMessage()
                    .contains("larger than"));
        }
        // more entries than allowed
        byte[] digest = new byte[32];
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(digest,
                (peer, payload) -> new Manifest(0, digest, MirrorPaths.MAX_TEMPLATE_FILES + 1, 0, List.of()).write(),
                (peer, payload) -> new byte[0]))) {
            assertTrue(assertThrows(MirrorException.class, () -> sink().sync(pair.member)).getMessage()
                    .contains("entries"));
        }
        // a template list above the total
        ClusterHandlers huge = ClusterHandlers.builder().request(MirrorMessages.TEMPLATE_LIST, (peer, payload) -> {
            List<TemplateEntry> list = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                list.add(new TemplateEntry("T" + i, digest, 1, MirrorPaths.MAX_TEMPLATE_BYTES));
            }
            return new TemplateList(list).write();
        }).build();
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, huge)) {
            assertTrue(assertThrows(MirrorException.class, () -> sink().sync(pair.member)).getMessage()
                    .contains("limits"));
        }
        // a digest that is not the digest of the entries
        List<FileEntry> one = List.of(new FileEntry("a.txt", 5, sha("hello")));
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(digest,
                (peer, payload) -> new Manifest(0, digest, 1, 0, one).write(), (peer, payload) -> new byte[0]))) {
            assertTrue(assertThrows(MirrorException.class, () -> sink().sync(pair.member)).getMessage()
                    .contains("digest"));
        }
        assertFalse(Files.exists(dir.resolve("local/templates/Lobby")));
    }

    @Test
    void secretLookingFilesAndFilesInsideFilesAreRefused() throws Exception {
        Map<List<FileEntry>, String> cases = Map.of(
                List.of(new FileEntry("forwarding.secret", 5, sha("hello"))), "looks like a secret",
                List.of(new FileEntry(".git/config", 5, sha("hello"))), "looks like a secret",
                List.of(new FileEntry("a", 5, sha("hello")), new FileEntry("a/b", 5, sha("hello"))), "inside a file");
        for (Map.Entry<List<FileEntry>, String> test : cases.entrySet()) {
            List<FileEntry> entries = test.getKey();
            byte[] digest = MirrorMessages.digest(entries);
            try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(digest, entries.size(),
                    5L * entries.size(), (peer, payload) -> new Manifest(0, digest, entries.size(), 0, entries).write(),
                    (peer, payload) -> "hello".getBytes(StandardCharsets.UTF_8)))) {
                MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
                assertTrue(e.getMessage().contains(test.getValue()), e.getMessage());
            }
        }
        assertFalse(Files.exists(dir.resolve("local/templates/Lobby")));
    }

    @Test
    void secretLookingNamesAreRecognised() {
        for (String path : List.of("forwarding.secret", "Forwarding.Secret", "forwarding.secret.bak", "vectolyn.env",
                ".env", ".env.local", "prod.env", "plugins/x/key.pem", "cert.PEM", "server.key", "store.p12",
                "store.pfx", "keystore.jks", "a.keystore", "db.kdbx", "id_rsa", "home/id_ed25519", ".netrc",
                ".git-credentials", ".pgpass", "secrets/a.txt", "a/Secrets/b.txt", ".git/config", "x/.ssh/known_hosts",
                ".gnupg/pubring.kbx")) {
            assertTrue(MirrorPaths.looksSecret(path), path);
        }
        for (String path : List.of("server.properties", "plugins/LuckPerms/config.yml", "environment.yml",
                "keys/readme.txt", "secret-garden.yml", "plugins/a.jar", "world/level.dat", "git/notes.txt")) {
            assertFalse(MirrorPaths.looksSecret(path), path);
        }
        assertTrue(MirrorPaths.isSecretFolder(".GIT"));
        assertFalse(MirrorPaths.isSecretFolder("plugins"));
    }

    @Test
    void aManifestThatDoesNotMatchTheListIsRefused() throws Exception {
        // The list says 5 bytes, the manifest has 10: a head must not list small and send large.
        List<FileEntry> entries = List.of(new FileEntry("a.txt", 5, sha("hello")),
                new FileEntry("b.txt", 5, sha("hello")));
        byte[] digest = MirrorMessages.digest(entries);
        for (int[] list : new int[][] {{1, 10}, {2, 5}}) {
            try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(digest, list[0], list[1],
                    (peer, payload) -> new Manifest(0, digest, entries.size(), 0, entries).write(),
                    (peer, payload) -> "hello".getBytes(StandardCharsets.UTF_8)))) {
                MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
                assertTrue(e.getMessage().contains("does not match the template list"), e.getMessage());
            }
        }
        assertFalse(Files.exists(dir.resolve("local/templates/Lobby")));
        assertFalse(Files.exists(dir.resolve("local/.cluster-sync/staging/Lobby")));
        // With the right totals the same template is taken.
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head(digest, 2, 10,
                (peer, payload) -> new Manifest(0, digest, entries.size(), 0, entries).write(),
                (peer, payload) -> "hello".getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(1, sink().sync(pair.member).updated());
        }
        assertEquals("hello", Files.readString(dir.resolve("local/templates/Lobby/b.txt")));
    }

    @Test
    void anUnreadableTemplateOnTheHeadKeepsItsLastCatalogEntry() throws Exception {
        Path root = Files.createDirectories(dir.resolve("head/Lobby"));
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.writeString(root.resolve("b.txt"), "world");
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
        TemplateSource source = new TemplateSource(dir.resolve("head"), file -> {
            if (broken.get() && file.getFileName().toString().equals("b.txt")) {
                throw new java.nio.file.AccessDeniedException(file.toString());
            }
            return Files.newByteChannel(file);
        });
        assertTrue(source.rescan());
        TemplateSource.Template before = source.catalog().get("Lobby");
        assertEquals(2, before.entries().size());

        // b.txt changes and cannot be read: the template is offered as it was, not without b.txt.
        Files.writeString(root.resolve("b.txt"), "world, changed");
        broken.set(true);
        assertFalse(source.rescan(), "nothing changed for the members");
        assertTrue(java.util.Arrays.equals(before.digest(), source.catalog().get("Lobby").digest()));
        assertEquals(2, source.catalog().get("Lobby").entries().size());

        // Readable again: the change is offered.
        broken.set(false);
        assertTrue(source.rescan());
        assertFalse(java.util.Arrays.equals(before.digest(), source.catalog().get("Lobby").digest()));
    }

    @Test
    void anUnreadableNewTemplateIsNotOffered() throws Exception {
        Files.writeString(Files.createDirectories(dir.resolve("head/Lobby")).resolve("a.txt"), "hello");
        TemplateSource source = new TemplateSource(dir.resolve("head"), file -> {
            throw new java.nio.file.AccessDeniedException(file.toString());
        });
        assertFalse(source.rescan());
        assertTrue(source.catalog().isEmpty());
    }

    @Test
    void theHeadRefusesTheListUntilItsFirstScanFinished() throws Exception {
        Files.writeString(Files.createDirectories(dir.resolve("head/Lobby")).resolve("a.txt"), "hello");
        TemplateSource source = new TemplateSource(dir.resolve("head"));
        byte[] request = new MirrorMessages.ManifestRequest("", new byte[0], 0).write();
        assertEquals(ClusterRpcException.NOT_READY,
                assertThrows(ClusterRpcException.class, () -> source.list(request)).code());
        source.rescan();
        assertEquals(List.of("Lobby"), TemplateList.read(new PacketReader(source.list(request)))
                .templates().stream().map(TemplateEntry::name).toList());
    }

    @Test
    void aHeadThatIsNotReadyMakesTheMemberRemoveNothing() throws Exception {
        Path lobby = Files.createDirectories(dir.resolve("local/templates/Lobby"));
        Files.writeString(lobby.resolve("a.txt"), "old");
        MirrorState.writeTemplates(dir.resolve("state/cluster/templates"), Map.of("Lobby", new byte[32]));
        TemplateSource notScanned = new TemplateSource(dir.resolve("head"));
        ClusterHandlers head = ClusterHandlers.builder()
                .request(MirrorMessages.TEMPLATE_LIST, (peer, payload) -> notScanned.list(payload))
                .build();
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
            assertThrows(IOException.class, () -> sink().sync(pair.member));
        }
        assertEquals("old", Files.readString(lobby.resolve("a.txt")));
    }

    @Test
    void aListingWithoutMostSyncedTemplatesRemovesNothing() throws Exception {
        Map<String, byte[]> synced = new TreeMap<>();
        for (String name : List.of("Lobby", "Proxy", "Game")) {
            Files.writeString(Files.createDirectories(dir.resolve("local/templates/" + name)).resolve("a.txt"), name);
            synced.put(name, sha(name));
        }
        Path state = dir.resolve("state/cluster/templates");
        MirrorState.writeTemplates(state, synced);
        for (List<TemplateEntry> listing : List.of(List.<TemplateEntry>of(),
                List.of(new TemplateEntry("Lobby", sha("Lobby"), 1, 5)))) {
            ClusterHandlers head = ClusterHandlers.builder()
                    .request(MirrorMessages.TEMPLATE_LIST, (peer, payload) -> new TemplateList(listing).write())
                    .build();
            try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
                MirrorException e = assertThrows(MirrorException.class, () -> sink().sync(pair.member));
                assertTrue(e.getMessage().contains("kept"), e.getMessage());
            }
            for (String name : synced.keySet()) {
                assertTrue(Files.isDirectory(dir.resolve("local/templates/" + name)), name);
            }
            assertEquals(synced.keySet(), MirrorState.readTemplates(state).keySet(), "still known as synced");
        }
        // One of three may go: an operator who deleted a template on the head.
        ClusterHandlers head = ClusterHandlers.builder()
                .request(MirrorMessages.TEMPLATE_LIST, (peer, payload) -> new TemplateList(List.of(
                        new TemplateEntry("Lobby", sha("Lobby"), 1, 5), new TemplateEntry("Proxy", sha("Proxy"), 1, 5)))
                        .write())
                .build();
        try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
            assertEquals(1, sink().sync(pair.member).removed());
        }
        assertFalse(Files.exists(dir.resolve("local/templates/Game")));
        assertTrue(Files.isDirectory(dir.resolve("local/templates/Lobby")));
    }

    @Test
    void theShareOfTemplatesOneListingMayRemoveIsLimited() {
        assertTrue(TemplateSink.removalAllowed(0, 0, true));
        assertTrue(TemplateSink.removalAllowed(1, 1, false));
        assertFalse(TemplateSink.removalAllowed(1, 1, true), "an empty listing removes nothing");
        assertTrue(TemplateSink.removalAllowed(2, 4, false));
        assertFalse(TemplateSink.removalAllowed(3, 4, false));
        assertFalse(TemplateSink.removalAllowed(2, 3, false));
    }

    @Test
    void theHeadServesOnlyManifestPathsAndNoLinks() throws Exception {
        Path root = Files.createDirectories(dir.resolve("head/Lobby"));
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.writeString(root.resolve("forwarding.secret"), "secret");
        TemplateSource source = new TemplateSource(dir.resolve("head"));
        assertTrue(source.rescan());
        TemplateSource.Template template = source.catalog().get("Lobby");
        assertEquals(List.of("a.txt"), template.entries().stream().map(FileEntry::path).toList());
        byte[] request = new MirrorMessages.ChunkRequest("Lobby", template.digest(), "forwarding.secret", 0, 10).write();
        assertEquals(ClusterRpcException.NOT_FOUND,
                assertThrows(ClusterRpcException.class, () -> source.chunk(request)).code());
        byte[] wrongDigest = new MirrorMessages.ChunkRequest("Lobby", new byte[32], "a.txt", 0, 10).write();
        assertEquals(ClusterRpcException.CHANGED,
                assertThrows(ClusterRpcException.class, () -> source.chunk(wrongDigest)).code());
        assertEquals(ClusterRpcException.BAD_REQUEST,
                assertThrows(ClusterRpcException.class, () -> source.chunk(new byte[] {1, 2, 3})).code());

        Path outside = Files.writeString(dir.resolve("outside.txt"), "HELLO");
        Files.delete(root.resolve("a.txt"));
        ClusterMirrorTest.link(root.resolve("a.txt"), outside);
        byte[] chunk = new MirrorMessages.ChunkRequest("Lobby", template.digest(), "a.txt", 0, 5).write();
        assertEquals(ClusterRpcException.CHANGED,
                assertThrows(ClusterRpcException.class, () -> source.chunk(chunk)).code(),
                "a file replaced by a link after the scan is not read");
        assertTrue(source.rescan());
        assertTrue(source.catalog().get("Lobby").entries().isEmpty(), "a rescan leaves the link out");
    }

    @Test
    void theHeadNeverScansThroughALinkedFolder() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path root = Files.createDirectories(dir.resolve("head/Lobby/plugins"));
        Files.writeString(root.resolve("a.txt"), "hello");
        ClusterMirrorTest.link(root.resolve("linked"), outside);
        TemplateSource source = new TemplateSource(dir.resolve("head"));
        assertTrue(source.rescan());
        assertEquals(List.of("plugins/a.txt"),
                source.catalog().get("Lobby").entries().stream().map(FileEntry::path).toList());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theHeadNeverScansThroughAJunction() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path root = Files.createDirectories(dir.resolve("head/Lobby/plugins"));
        Files.writeString(root.resolve("a.txt"), "hello");
        ClusterMirrorTest.junction(root.resolve("joined"), outside);
        try {
            TemplateSource source = new TemplateSource(dir.resolve("head"));
            assertTrue(source.rescan());
            assertEquals(List.of("plugins/a.txt"),
                    source.catalog().get("Lobby").entries().stream().map(FileEntry::path).toList());
        } finally {
            Files.delete(root.resolve("joined"));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletingATemplateNeverFollowsANestedJunction() throws Exception {
        Path keep = Files.createDirectories(dir.resolve("keep"));
        Files.writeString(keep.resolve("precious.txt"), "precious");
        Path template = Files.createDirectories(dir.resolve("local/templates/Gone/plugins"));
        Files.writeString(template.resolve("a.txt"), "a");
        ClusterMirrorTest.junction(template.resolve("shared"), keep);
        TemplateSink.deleteTree(dir.resolve("local/templates/Gone"));
        assertFalse(Files.exists(dir.resolve("local/templates/Gone")));
        assertEquals("precious", Files.readString(keep.resolve("precious.txt")));
    }

    @Test
    void aLinkedSyncFolderIsRefused() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path old = Files.createDirectories(elsewhere.resolve("old/Lobby"));
        Files.writeString(old.resolve("a.txt"), "not the sync's");
        Files.createDirectories(dir.resolve("local/templates/Lobby"));
        Files.createDirectories(dir.resolve("local"));
        ClusterMirrorTest.link(dir.resolve("local/.cluster-sync"), elsewhere);
        assertThrows(IOException.class, () -> sink().recover());
        assertEquals("not the sync's", Files.readString(old.resolve("a.txt")));
    }

    @Test
    void deletingATemplateNeverFollowsALink() throws Exception {
        Path keep = Files.createDirectories(dir.resolve("keep"));
        Files.writeString(keep.resolve("precious.txt"), "precious");
        Path template = Files.createDirectories(dir.resolve("local/templates/Gone"));
        Files.createDirectories(template.resolve("nested"));
        ClusterMirrorTest.link(template.resolve("link"), keep);
        ClusterMirrorTest.link(template.resolve("nested/link"), keep);
        TemplateSink.deleteTree(template);
        assertFalse(Files.exists(template));
        assertEquals("precious", Files.readString(keep.resolve("precious.txt")));
    }

    @Test
    void anInterruptedSwapIsPutRight() throws Exception {
        // renamed away, not replaced: comes back
        Path old = Files.createDirectories(dir.resolve("local/.cluster-sync/old/Lobby"));
        Files.writeString(old.resolve("a.txt"), "old");
        // replaced already: the old copy goes
        Path stale = Files.createDirectories(dir.resolve("local/.cluster-sync/old/Proxy"));
        Files.writeString(stale.resolve("b.txt"), "stale");
        Path current = Files.createDirectories(dir.resolve("local/templates/Proxy"));
        Files.writeString(current.resolve("b.txt"), "new");
        Files.createDirectories(dir.resolve("local/.cluster-sync/staging/Half"));

        sink().recover();

        assertEquals("old", Files.readString(dir.resolve("local/templates/Lobby/a.txt")));
        assertEquals("new", Files.readString(dir.resolve("local/templates/Proxy/b.txt")));
        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(dir.resolve("local/.cluster-sync/staging")));
    }

    @Test
    void aConfigManifestWithCloudYmlOrTooMuchIsRefused() throws Exception {
        try (ConfigService config = ConfigService.open(CloudHome.of(dir.resolve("member")))) {
            config.mirrorMode(true);
            ConfigMirrorSink sink = new ConfigMirrorSink(config, dir.resolve("member/state/cluster/config-applied"));
            byte[] cloud = Files.readAllBytes(dir.resolve("member/config/cloud.yml"));
            List<List<FileEntry>> bad = List.of(
                    List.of(new FileEntry("cloud.yml", cloud.length, MirrorMessages.sha256(cloud))),
                    List.of(new FileEntry("nodes.yml", MirrorPaths.MAX_CONFIG_FILE_BYTES + 1, sha("x"))),
                    List.of(new FileEntry("groups/lobby.yml", 5, sha("hello"))));
            for (List<FileEntry> entries : bad) {
                byte[] digest = MirrorMessages.digest(entries);
                ClusterHandlers head = ClusterHandlers.builder()
                        .request(MirrorMessages.CONFIG_MANIFEST, (peer, payload) ->
                                new Manifest(3, digest, entries.size(), 0, entries).write())
                        .request(MirrorMessages.CONFIG_CHUNK, (peer, payload) -> cloud)
                        .build();
                try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
                    assertThrows(MirrorException.class, () -> sink.sync(pair.member), entries.toString());
                }
            }
            assertEquals(ConfigSnapshot.defaults().nodes(), config.snapshot().nodes(), "nothing taken over");
            assertFalse(Files.exists(dir.resolve("member/state/cluster/config-applied")));
            assertTrue(config.snapshot().groups().isEmpty());
        }
    }

    @Test
    void revisionsAboveWhatTheMemberCanStoreAreRefused() {
        byte[] digest = new byte[32];
        assertThrows(IllegalArgumentException.class,
                () -> new Manifest(MirrorMessages.MAX_REVISION + 1, digest, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MirrorMessages.Changed(MirrorMessages.MAX_REVISION + 1));
        byte[] raw = new PacketWriter().writeVarLong(Long.MAX_VALUE).writeByteArray(digest, 32).writeVarInt(0)
                .writeVarInt(0).writeVarInt(0).toByteArray();
        assertThrows(net.vectolyn.protocol.ProtocolException.class, () -> Manifest.read(new PacketReader(raw)));
        assertEquals(MirrorMessages.MAX_REVISION,
                new Manifest(MirrorMessages.MAX_REVISION, digest, 0, 0, List.of()).revision());
    }

    @Test
    void aBrokenRecordOfTheAppliedRevisionStopsTheConfigMirror() throws Exception {
        try (ConfigService config = ConfigService.open(CloudHome.of(dir.resolve("member")))) {
            config.mirrorMode(true);
            Path state = dir.resolve("member/state/cluster/config-applied");
            Files.createDirectories(state.getParent());
            Files.writeString(state, "revision=garbage\n");
            ConfigMirrorSink sink = new ConfigMirrorSink(config, state);
            byte[] nodes = ConfigMirrorSource.render(ConfigSnapshot.defaults()).get(CloudHome.NODES_FILE);
            List<FileEntry> entries = List.of(new FileEntry(CloudHome.NODES_FILE, nodes.length,
                    MirrorMessages.sha256(nodes)));
            byte[] digest = MirrorMessages.digest(entries);
            ClusterHandlers head = ClusterHandlers.builder()
                    .request(MirrorMessages.CONFIG_MANIFEST, (peer, payload) ->
                            new Manifest(1, digest, entries.size(), 0, entries).write())
                    .request(MirrorMessages.CONFIG_CHUNK, (peer, payload) -> nodes)
                    .build();
            try (SessionPair pair = new SessionPair(ClusterHandlers.NONE, head)) {
                MirrorException e = assertThrows(MirrorException.class, () -> sink.sync(pair.member));
                assertTrue(e.getMessage().contains("config-applied cannot be read"), e.getMessage());
                // Deleting the record lets the member take over the head's config again.
                Files.delete(state);
                sink.sync(pair.member);
            }
            assertTrue(Files.readString(state).startsWith("revision=1\n"));
        }
    }
}
