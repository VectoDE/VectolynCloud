package net.vectolyn.cloud.download.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.vectolyn.cloud.download.hash.Checksum;
import net.vectolyn.cloud.download.hash.HashAlgorithm;
import net.vectolyn.cloud.download.provider.ArtifactCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoftwareLockFileTest {

    private static final String SHA256_A = "416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15";
    private static final String SHA256_B = "1373c651510d4aeee7e7903f3cfd7003919c1a364528dbe124a4ae40e11a766e";
    private static final String SHA256_C = "70446947655b4f8f286aba896aa4c19a255e8ca8a730636ef2a6017ae4415d95";
    private static final String SHA512 = "c6af11f50e14bc1ba239f5a90be653cce85feee30c473a4b53336b4d5527d790"
            + "369cf43843f7cb06d3ac3a34fc207e7203436737ca719070644101472453401b";

    @TempDir
    Path home;

    static LockedArtifact paper(String build, String sha256) {
        Checksum checksum = new Checksum(HashAlgorithm.SHA256, sha256);
        return new LockedArtifact(new ArtifactCoordinate("papermc", "paper", "26.3", build),
                URI.create("https://fill-data.papermc.io/v1/objects/" + sha256 + "/paper-26.3-" + build + ".jar"),
                "paper-26.3-" + build + ".jar", checksum, checksum, Trust.SOURCE, 55257780, null);
    }

    static LockedArtifact fabric() {
        Checksum sha256 = new Checksum(HashAlgorithm.SHA256, SHA256_C);
        return new LockedArtifact(new ArtifactCoordinate("fabric", "fabric-server", "26.3", "loader-0.20.1+installer-1.1.2"),
                URI.create("https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar"),
                "fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar", sha256, sha256, Trust.TOFU, 181838, null);
    }

    static LockedArtifact viaVersion() {
        return new LockedArtifact(new ArtifactCoordinate("modrinth", "viaversion", "5.12.2", null),
                URI.create("https://cdn.modrinth.com/data/P1OZGk5p/versions/Fkfcr89K/ViaVersion-5.12.2.jar"),
                "ViaVersion-5.12.2.jar", new Checksum(HashAlgorithm.SHA512, SHA512),
                new Checksum(HashAlgorithm.SHA256, SHA256_B), Trust.SOURCE, 6508809, null);
    }

    @Test
    void roundTripKeepsEveryField() throws Exception {
        SoftwareLock lock = SoftwareLock.empty()
                .put("paper", paper("226", SHA256_B))
                .put("paper", paper("227", SHA256_A))
                .put("fabric-server", fabric())
                .put("viaversion", viaVersion());
        Path file = home.resolve(SoftwareLockFile.FILE_NAME);
        SoftwareLockFile.write(file, lock);
        SoftwareLock read = SoftwareLockFile.read(file);
        assertEquals(lock, read);
        assertEquals("226", read.get("paper").orElseThrow().previous().coordinate().build());

        String text = Files.readString(file);
        assertTrue(text.startsWith("# Written by VectolynCloud"), text);
        assertTrue(text.contains("version: \"26.3\""), "versions must stay strings:\n" + text);
        assertTrue(text.contains("trust: tofu"), text);
        assertTrue(text.contains("hash-type: sha512"), text);
    }

    @Test
    void valuesThatLookLikeOtherYamlTypesStayStrings() throws Exception {
        SoftwareLock lock = SoftwareLock.empty();
        int i = 0;
        for (String tricky : new String[] {"26.3", "1.10", "1e5", "0x10", "yes", "no", "on", "true", "null", "~", "227",
            "-1", ".5", "1_000", "2026-10-28", "a: b", "#x", "[1]", "{a}", "*ref", "&anchor", "!tag", "' '", "@x", "`x"}) {
            Checksum sha256 = new Checksum(HashAlgorithm.SHA256, SHA256_A);
            lock = lock.put("tricky-" + i++, new LockedArtifact(new ArtifactCoordinate("papermc", "paper", tricky, tricky),
                    URI.create("https://fill-data.papermc.io/x.jar"),
                    // File names must be base names; ':' and '*' are not allowed there.
                    net.vectolyn.cloud.download.FileNames.isSafeBaseName(tricky) ? tricky : "f" + i + ".jar",
                    sha256, sha256, Trust.SOURCE, 1, null));
        }
        assertEquals(lock, SoftwareLockFile.parse(SoftwareLockFile.format(lock)));
    }

    @Test
    void missingFileIsAnEmptyLock() throws Exception {
        assertEquals(SoftwareLock.empty(), SoftwareLockFile.read(home.resolve("nope.yml")));
    }

    @Test
    void putKeepsOnlyOnePreviousAndRollbackSwaps() {
        SoftwareLock lock = SoftwareLock.empty()
                .put("paper", paper("225", SHA256_C))
                .put("paper", paper("226", SHA256_B))
                .put("paper", paper("227", SHA256_A));
        LockedArtifact current = lock.get("paper").orElseThrow();
        assertEquals("227", current.coordinate().build());
        assertEquals("226", current.previous().coordinate().build());
        assertNull(current.previous().previous());

        SoftwareLock rolledBack = lock.rollback("paper");
        assertEquals("226", rolledBack.get("paper").orElseThrow().coordinate().build());
        assertEquals("227", rolledBack.get("paper").orElseThrow().previous().coordinate().build());
        assertThrows(IllegalStateException.class, () -> SoftwareLock.empty().put("x", fabric()).rollback("x"));
    }

    @Test
    void puttingTheSameFileAgainKeepsThePrevious() {
        SoftwareLock lock = SoftwareLock.empty().put("paper", paper("226", SHA256_B)).put("paper", paper("227", SHA256_A));
        SoftwareLock again = lock.put("paper", paper("227", SHA256_A));
        assertEquals("226", again.get("paper").orElseThrow().previous().coordinate().build());
    }

    @Test
    void strictParsing() {
        String valid = SoftwareLockFile.format(SoftwareLock.empty().put("paper", paper("227", SHA256_A)));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace("version: \"26.3\"", "version: 26.3")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace("schema-version: 1", "schema-version: 2")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace("trust: source", "trust: maybe")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace("    size:", "    colour: red\n    size:")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace("https://fill-data", "http://fill-data")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(valid.replace(SHA256_A + "\n", "abc\n")));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse("schema-version: 1\nschema-version: 1\n"));
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse("[1, 2"));
    }

    @Test
    void tofuEntryMustPinItsOwnSha256() {
        String valid = SoftwareLockFile.format(SoftwareLock.empty().put("fabric-server", fabric()));
        String broken = valid.replaceFirst("hash: \"?" + SHA256_C, "hash: " + SHA256_A);
        assertThrows(LockFileException.class, () -> SoftwareLockFile.parse(broken));
    }

    @Test
    void namesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> SoftwareLock.of(Map.of("../evil", fabric())));
        assertThrows(IllegalArgumentException.class, () -> SoftwareLock.empty().put("Paper", fabric()));
    }

    @Test
    void fileNameWithAPathIsRefused() throws Exception {
        Checksum sha256 = new Checksum(HashAlgorithm.SHA256, SHA256_A);
        String yaml = SoftwareLockFile.format(SoftwareLock.empty().put("paper", new LockedArtifact(
                new ArtifactCoordinate("papermc", "paper", "26.3", "227"), URI.create("https://fill-data.papermc.io/x.jar"),
                "paper.jar", sha256, sha256, Trust.SOURCE, 1, null)));
        assertTrue(yaml.contains("file-name: paper.jar"), yaml);
        assertEquals(1, SoftwareLockFile.parse(yaml).entries().size());
        for (String bad : new String[] {"../../paper.jar", "plugins/paper.jar", "nul.jar", "'..'"}) {
            LockFileException e = assertThrows(LockFileException.class,
                    () -> SoftwareLockFile.parse(yaml.replace("file-name: paper.jar", "file-name: " + bad)));
            assertTrue(e.getMessage().contains("fileName"), e.getMessage());
        }
    }
}
