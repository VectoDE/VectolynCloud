package net.vectora.cloud.download.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SafeExtractorTest {

    @TempDir
    Path temp;

    private static final SafeExtractor EXTRACTOR = new SafeExtractor(ExtractionLimits.defaults());

    private Path zip(String... namesAndContents) throws IOException {
        Path file = temp.resolve("test" + System.nanoTime() + ".zip");
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private SafeExtractor.Result extractTar(byte[] tar, Path target) throws IOException {
        return EXTRACTOR.extractTar(new ByteArrayInputStream(tar), target);
    }

    @Test
    void extractsAZip() throws Exception {
        Path target = temp.resolve("out");
        SafeExtractor.Result result = EXTRACTOR.extractZip(zip("mariadb/", "", "mariadb/bin/mariadbd.exe", "MZ"), target);
        assertEquals("MZ", Files.readString(target.resolve("mariadb/bin/mariadbd.exe")));
        assertEquals(1, result.files());
    }

    @Test
    void zipSlipIsRejectedAndNothingIsLeft() throws Exception {
        Path target = temp.resolve("out");
        Path archive = zip("ok.txt", "fine", "../evil.txt", "pwned");
        ArchiveException e = assertThrows(ArchiveException.class, () -> EXTRACTOR.extractZip(archive, target));
        assertTrue(e.getMessage().contains(".."), e.getMessage());
        assertFalse(Files.exists(temp.resolve("evil.txt")));
        assertFalse(Files.exists(target), "a failed extraction removes what it wrote");
    }

    @Test
    void absoluteAndBackslashNamesAreRejected() throws Exception {
        for (String name : new String[] {"/etc/passwd", "..\\evil.txt", "C:/Windows/evil.txt", "a/../../evil.txt"}) {
            Path target = temp.resolve("out-" + Math.abs(name.hashCode()));
            Path archive = zip(name, "x");
            assertThrows(ArchiveException.class, () -> EXTRACTOR.extractZip(archive, target), name);
        }
    }

    @Test
    void zipBombByTotalSizeIsStopped() throws Exception {
        Path file = temp.resolve("bomb.zip");
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("zeros.bin"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 20; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }
        assertTrue(Files.size(file) < 100_000, "the bomb itself is small");
        SafeExtractor small = new SafeExtractor(new ExtractionLimits(100, 1024 * 1024, 1024 * 1024 * 1024));
        Path target = temp.resolve("out");
        ArchiveException e = assertThrows(ArchiveException.class, () -> small.extractZip(file, target));
        assertTrue(e.getMessage().contains("more than"), e.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void zipBombByEntryCountIsStopped() throws Exception {
        String[] entries = new String[2 * 50];
        for (int i = 0; i < 50; i++) {
            entries[2 * i] = "f" + i;
            entries[2 * i + 1] = "";
        }
        Path archive = zip(entries);
        SafeExtractor small = new SafeExtractor(new ExtractionLimits(10, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(ArchiveException.class, () -> small.extractZip(archive, temp.resolve("out")));
    }

    @Test
    void perEntryLimit() {
        SafeExtractor small = new SafeExtractor(new ExtractionLimits(10, Long.MAX_VALUE, 10));
        byte[] tar = new TarBuilder().file("big", "x".repeat(11)).build();
        assertThrows(ArchiveException.class, () -> small.extractTar(new ByteArrayInputStream(tar), temp.resolve("out")));
    }

    @Test
    void refusesNonEmptyTarget() throws Exception {
        Path target = Files.createDirectories(temp.resolve("busy"));
        Files.writeString(target.resolve("keep.txt"), "mine");
        assertThrows(ArchiveException.class, () -> EXTRACTOR.extractZip(zip("a", "b"), target));
        assertEquals("mine", Files.readString(target.resolve("keep.txt")));
    }

    @Test
    void extractsATarGzWithHardlink() throws Exception {
        byte[] gz = new TarBuilder().gnu().directory("mariadb/").file("mariadb/bin/mariadbd", "ELF".getBytes(), 0755)
                .hardlink("mariadb/bin/mysqld", "mariadb/bin/mariadbd").buildGzip();
        Path archive = temp.resolve("m.tar.gz");
        Files.write(archive, gz);
        Path target = temp.resolve("out");
        SafeExtractor.Result result = EXTRACTOR.extractTarGz(archive, target);
        assertEquals("ELF", Files.readString(target.resolve("mariadb/bin/mysqld")));
        assertEquals(2, result.files());
    }

    @Test
    void tarSlipIsRejected() {
        byte[] tar = new TarBuilder().file("../../evil", "x").build();
        assertThrows(ArchiveException.class, () -> extractTar(tar, temp.resolve("out")));
        assertFalse(Files.exists(temp.resolve("evil")));
    }

    @Test
    void symlinkOutsideIsRejected() {
        for (String link : new String[] {"../../etc", "/etc/passwd", "a/../../../x"}) {
            byte[] tar = new TarBuilder().directory("a/").symlink("a/link", link).build();
            Path target = temp.resolve("out-" + Math.abs(link.hashCode()));
            assertThrows(ArchiveException.class, () -> extractTar(tar, target), link);
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void hardlinkMustPointToAnExtractedFile() {
        byte[] tar = new TarBuilder().hardlink("copy", "../../etc/passwd").build();
        assertThrows(ArchiveException.class, () -> extractTar(tar, temp.resolve("out")));
        byte[] unknown = new TarBuilder().hardlink("copy", "not-there").build();
        assertThrows(ArchiveException.class, () -> extractTar(unknown, temp.resolve("out2")));
    }

    @Test
    void fileAndDirectoryConflict() {
        byte[] tar = new TarBuilder().file("a", "x").directory("a/").build();
        assertThrows(ArchiveException.class, () -> extractTar(tar, temp.resolve("out")));
        byte[] duplicate = new TarBuilder().file("a", "x").file("a", "y").build();
        assertThrows(ArchiveException.class, () -> extractTar(duplicate, temp.resolve("out2")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating symlinks needs extra rights on Windows")
    void symlinkInsideIsCreatedAfterTheFiles() throws Exception {
        byte[] tar = new TarBuilder().gnu().directory("lib/").file("lib/libmariadb.so.3", "so")
                .symlink("lib/libmariadb.so", "libmariadb.so.3").build();
        Path target = temp.resolve("out");
        SafeExtractor.Result result = extractTar(tar, target);
        assertEquals(1, result.symlinks());
        assertTrue(Files.isSymbolicLink(target.resolve("lib/libmariadb.so")));
        assertEquals("so", Files.readString(target.resolve("lib/libmariadb.so")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating symlinks needs extra rights on Windows")
    void chainedSymlinksThatEscapeAreRejected() {
        // "x" -> "." is fine on its own, but "a" -> "x/.." then points to the parent of the target.
        byte[] tar = new TarBuilder().symlink("x", ".").symlink("a", "x/..").build();
        Path target = temp.resolve("out");
        assertThrows(ArchiveException.class, () -> extractTar(tar, target));
        assertFalse(Files.exists(target));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void appliesExecutableBitWithoutSetuid() throws Exception {
        byte[] tar = new TarBuilder().file("run", "x".getBytes(), 04755).build();
        Path target = temp.resolve("out");
        extractTar(tar, target);
        assertTrue(Files.isExecutable(target.resolve("run")));
    }
}
