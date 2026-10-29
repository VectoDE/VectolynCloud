package net.vectora.cloud.backup.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.vectora.cloud.backup.manifest.BackupManifest.DatabaseEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.FileEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.ServiceEntry;
import net.vectora.cloud.backup.manifest.BackupManifest.SkippedEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManifestCodecTest {

    static final String HASH = "a".repeat(64);

    static BackupManifest manifest(List<FileEntry> files, DatabaseEntry database) {
        return new BackupManifest(1, "20261029-031500-1a2b", "2026-10-29T03:15:00Z", "manual", "1.0.0", "note",
                List.of(new ServiceEntry("lobby-1", "lobby", "26.3", "saved"),
                        new ServiceEntry("survival-1", "survival", "26.3", "skipped")),
                database, files, List.of(new SkippedEntry("services/lobby-1/odd:name", "name is not portable")));
    }

    static BackupManifest valid() {
        return manifest(List.of(new FileEntry("services/lobby-1/world/level.dat", 12, HASH),
                        new FileEntry("database/luckperms.sql", 3, HASH)),
                new DatabaseEntry("managed", "dumped", "database/luckperms.sql", "mariadb-dump"));
    }

    private static String json(BackupManifest manifest) {
        return new String(ManifestCodec.write(manifest), StandardCharsets.UTF_8);
    }

    private static String rejected(String json) {
        return assertThrows(ManifestException.class,
                () -> ManifestCodec.read(json.getBytes(StandardCharsets.UTF_8))).getMessage();
    }

    @Test
    void writesAndReadsBack() throws Exception {
        BackupManifest manifest = valid();
        String json = json(manifest);
        assertTrue(json.contains("\"created-at\" : \"2026-10-29T03:15:00Z\""), json);
        assertTrue(json.contains("\"minecraft-version\" : \"26.3\""), json);
        assertEquals(manifest, ManifestCodec.read(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(15, manifest.totalBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"services/lobby-1/../../evil", "services/lobby-1/a/../../../evil", "../evil",
        "/etc/passwd", "services/lobby-1//x", "services/lobby-1/a\\..\\b", "C:/Windows/x", "services/lobby-1/c:x",
        "services/lobby-1/CON", "services/lobby-1/nul.txt", "services/lobby-1/com1.dat", "services/lobby-1/trail.",
        "services/lobby-1/trail ", "services/lobby-1/x\u0000y", "services/lobby-1/x\ny", "services/lobby-1/*",
        "services/lobby-1/", "services/lobby-1/./x"})
    void unsafePathsAreRejected(String path) {
        String json = json(valid()).replace("services/lobby-1/world/level.dat", path.replace("\\", "\\\\")
                .replace("\u0000", "\\u0000").replace("\n", "\\n"));
        assertEquals("unsafe file path", rejected(json));
    }

    @Test
    void filesOutsideTheKnownFoldersOrServicesAreRejected() {
        String base = json(valid());
        assertEquals("files must lie in services/<id>/ or database/",
                rejected(base.replace("services/lobby-1/world/level.dat", "world/level.dat")));
        assertEquals("files must lie in services/<id>/ or database/",
                rejected(base.replace("database/luckperms.sql", "database/sub/luckperms.sql")));
        assertEquals("file of a service the manifest does not list",
                rejected(base.replace("services/lobby-1/world/level.dat", "services/other-1/level.dat")));
        assertEquals("file of a service marked as skipped or missing",
                rejected(base.replace("services/lobby-1/world/level.dat", "services/survival-1/level.dat")));
        assertEquals("a file must not replace the manifest",
                rejected(base.replace("services/lobby-1/world/level.dat", "manifest.json")));
    }

    @Test
    void duplicatesAreRejectedAlsoIgnoringCase() {
        BackupManifest twice = manifest(List.of(new FileEntry("services/lobby-1/World/level.dat", 1, HASH),
                new FileEntry("services/lobby-1/world/LEVEL.dat", 1, HASH)), null);
        String json = json(manifest(List.of(new FileEntry("services/lobby-1/World/level.dat", 1, HASH),
                new FileEntry("services/lobby-1/world/other.dat", 1, HASH)), null))
                .replace("world/other.dat", "world/LEVEL.dat");
        assertEquals("file listed twice (also case-insensitively)", rejected(json));
        assertThrows(IllegalArgumentException.class, () -> ManifestCodec.write(twice), "the writer refuses it too");
    }

    @Test
    void hashesSizesAndIdsAreChecked() {
        String base = json(valid());
        assertEquals("sha256 must be 64 lower-case hex digits", rejected(base.replace(HASH, "A".repeat(64))));
        assertEquals("sha256 must be 64 lower-case hex digits", rejected(base.replace(HASH, "a".repeat(63))));
        assertEquals("file size out of range", rejected(base.replace("\"size\" : 12", "\"size\" : -1")));
        assertTrue(rejected(base.replace("\"size\" : 12", "\"size\" : \"12\"")).startsWith("manifest is not valid JSON"));
        assertTrue(rejected(base.replace("20261029-031500-1a2b", "../../x")).startsWith("id must match"));
        assertEquals("created-at is not an ISO-8601 instant", rejected(base.replace("2026-10-29T03:15:00Z", "yesterday")));
        assertEquals("unknown reason", rejected(base.replace("\"manual\"", "\"evil\"")));
        assertEquals("unsupported schema-version", rejected(base.replace("\"schema-version\" : 1", "\"schema-version\" : 2")));
        assertEquals("service id must match " + net.vectora.cloud.backup.BackupTarget.SERVICE_ID,
                rejected(base.replace("\"id\" : \"lobby-1\"", "\"id\" : \"../lobby\"")));
        assertEquals("unknown consistency", rejected(base.replace("\"saved\"", "\"maybe\"")));
    }

    @Test
    void databaseEntryMustMatchTheFiles() {
        String base = json(valid());
        assertEquals("database file is not listed",
                rejected(base.replace("\"file\" : \"database/luckperms.sql\"", "\"file\" : \"database/other.sql\"")));
        assertEquals("unknown database mode", rejected(base.replace("\"managed\"", "\"sqlite\"")));
        assertEquals("database file without a dump", rejected(base.replace("\"dumped\"", "\"failed\"")));
    }

    @Test
    void structureIsStrict() {
        String base = json(valid());
        assertTrue(rejected(base.replaceFirst("\\{", "{ \"unknown\" : true,")).startsWith("manifest is not valid JSON"));
        assertTrue(rejected(base.replaceFirst("\\{", "{ \"id\" : \"20261029-031500-1a2b\",")).startsWith(
                "manifest is not valid JSON"), "duplicate key");
        assertTrue(rejected(base + " {}").startsWith("manifest is not valid JSON"), "trailing content");
        assertTrue(rejected(base.replace("\"files\" : [", "\"filez\" : [")).startsWith("manifest is not valid JSON"));
        assertTrue(rejected("[" + base + "]").startsWith("manifest is not valid JSON"));
        assertEquals("manifest is empty", rejected(""));
        assertTrue(rejected("null").startsWith("manifest is"));
        assertTrue(rejected("[[[[[[[[[[[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]]]]]]]]]]]").startsWith("manifest is not valid"));
        assertEquals("note is too long or has control characters",
                rejected(base.replace("\"note\" : \"note\"", "\"note\" : \"a\\u0007b\"")));
    }

    @Test
    void errorsNeverQuoteTheContent() {
        String secretish = "services/lobby-1/../SECRET-VALUE";
        String message = rejected(json(valid()).replace("services/lobby-1/world/level.dat", secretish));
        assertFalse(message.contains("SECRET-VALUE"), message);
        String broken = rejected("{\"schema-version\": 1, \"id\": \"SECRET-VALUE\" ");
        assertFalse(broken.contains("SECRET-VALUE"), broken);
    }

    @Test
    void safePathRulesAcceptOrdinaryWorldFiles() {
        for (String ok : List.of("services/lobby-1/world/region/r.0.0.mca", "services/lobby-1/world_nether/DIM-1/x",
                "services/lobby-1/plugins/LuckPerms/luckperms-h2-v2.mv.db", "database/luckperms.sql",
                "services/lobby-1/Wörld mit Leerzeichen/level.dat", "services/lobby-1/.hidden",
                "services/lobby-1/console.dat", "services/lobby-1/nullable")) {
            assertTrue(ManifestPaths.isSafe(ok), ok);
        }
    }
}
