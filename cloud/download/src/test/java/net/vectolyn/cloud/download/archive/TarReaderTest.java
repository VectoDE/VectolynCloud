package net.vectolyn.cloud.download.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TarReaderTest {

    private static List<String> read(byte[] tar) throws IOException {
        TarReader reader = new TarReader(new ByteArrayInputStream(tar));
        List<String> out = new ArrayList<>();
        TarEntry entry;
        while ((entry = reader.next()) != null) {
            String content = new String(reader.content().readAllBytes(), StandardCharsets.UTF_8);
            out.add(entry.type() + " " + entry.name() + (entry.linkName().isEmpty() ? "" : " -> " + entry.linkName())
                    + (content.isEmpty() ? "" : " = " + content));
        }
        return out;
    }

    private static byte[] seed(String name) throws IOException {
        try (var in = TarReaderTest.class.getResourceAsStream(
                "TarReaderFuzzTestInputs/readFailsOnlyWithArchiveException/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsArchivesWrittenByPythonTarfile() throws Exception {
        // The fuzz seeds were written by Python's tarfile module in its three formats.
        assertEquals(List.of("DIRECTORY bin/", "FILE bin/run = echo", "SYMLINK bin/link -> run",
                "HARDLINK bin/hard -> bin/run"), read(seed("ustar-files")));
        assertEquals(List.of("FILE mariadb/" + "deep/".repeat(25) + "file.txt = x"), read(seed("gnu-long-name")));
        assertEquals(List.of("FILE p/" + "ü".repeat(70) + "/f = y"), read(seed("pax-path")));
    }

    @Test
    void readsUstarEntries() throws Exception {
        byte[] tar = new TarBuilder().directory("bin/").file("bin/run.sh", "echo hi").symlink("bin/link", "run.sh")
                .hardlink("bin/hard", "bin/run.sh").build();
        assertEquals(List.of("DIRECTORY bin/", "FILE bin/run.sh = echo hi", "SYMLINK bin/link -> run.sh",
                "HARDLINK bin/hard -> bin/run.sh"), read(tar));
    }

    @Test
    void readsGnuLongNames() throws Exception {
        String longName = "mariadb-11.8.9-linux-systemd-x86_64/" + "deep/".repeat(30) + "file.txt";
        byte[] tar = new TarBuilder().gnu().gnuLongName(longName, "x").build();
        assertEquals(List.of("FILE " + longName + " = x"), read(tar));
    }

    @Test
    void readsPaxPath() throws Exception {
        String path = "pax/" + "ü".repeat(80) + "/file";
        byte[] tar = new TarBuilder().paxPath(path, "y").build();
        assertEquals(List.of("FILE " + path + " = y"), read(tar));
    }

    @Test
    void skipsUnreadContent() throws Exception {
        byte[] tar = new TarBuilder().file("a", "x".repeat(1000)).file("b", "y").build();
        TarReader reader = new TarReader(new ByteArrayInputStream(tar));
        assertEquals("a", reader.next().name());
        TarEntry b = reader.next();
        assertEquals("b", b.name());
        assertEquals("y", new String(reader.content().readAllBytes(), StandardCharsets.UTF_8));
        assertNull(reader.next());
        assertNull(reader.next());
    }

    @Test
    void rejectsBrokenChecksum() {
        byte[] tar = new TarBuilder().file("a", "x").build();
        tar[0] = 'b';
        assertThrows(ArchiveException.class, () -> read(tar));
    }

    @Test
    void rejectsUnknownMagic() {
        byte[] tar = new TarBuilder().file("a", "x").build();
        tar[257] = 'X';
        assertThrows(ArchiveException.class, () -> read(tar));
    }

    @Test
    void rejectsDevicesFifosAndSparseFiles() {
        for (char type : new char[] {'3', '4', '6', 'S', 'V'}) {
            byte[] tar = new TarBuilder().type("dev", type).build();
            assertThrows(ArchiveException.class, () -> read(tar), "type " + type);
        }
    }

    @Test
    void rejectsTruncatedStreams() {
        byte[] full = new TarBuilder().file("a", "x".repeat(600)).build();
        byte[] cut = java.util.Arrays.copyOf(full, 700);
        assertThrows(ArchiveException.class, () -> read(cut));
        byte[] noEnd = new TarBuilder().file("a", "x").buildWithoutEnd();
        assertThrows(ArchiveException.class, () -> read(noEnd));
    }

    @Test
    void rejectsHugeMetadata() {
        byte[] tar = new TarBuilder().entry("L", 'L', new byte[TarReader.MAX_METADATA_BYTES + 1], "", 0644).build();
        assertThrows(ArchiveException.class, () -> read(tar));
    }

    @Test
    void rejectsMalformedPax() {
        byte[] tar = new TarBuilder().entry("x", 'x', "99 path=a\n".getBytes(StandardCharsets.UTF_8), "", 0644)
                .file("a", "b").build();
        assertThrows(ArchiveException.class, () -> read(tar));
    }
}
