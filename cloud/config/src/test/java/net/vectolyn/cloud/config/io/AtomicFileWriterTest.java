package net.vectolyn.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {

    private static final byte[] OLD = "old content\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "new content\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void createsAndReplacesFiles() throws IOException {
        Path target = dir.resolve("sub/cloud.yml");
        new AtomicFileWriter().write(target, OLD);
        assertArrayEquals(OLD, Files.readAllBytes(target));
        new AtomicFileWriter().write(target, NEW);
        assertArrayEquals(NEW, Files.readAllBytes(target));
        assertEquals(List.of(target), listing(target.getParent()), "no temporary file is left behind");
    }

    @Test
    void abortBeforeTheRenameLeavesTheOldFileIntact() throws IOException {
        Path target = existing();
        IOException crash = new IOException("simulated crash after writing the temporary file");
        AtomicFileWriter writer = new AtomicFileWriter(Files::move);
        IOException thrown = assertThrows(IOException.class, () -> writer.write(target, NEW, temp -> {
            Files.write(temp, "half".getBytes(StandardCharsets.UTF_8));
            throw crash;
        }));
        assertSame(crash, thrown);
        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertEquals(List.of(target), listing(dir), "the temporary file is removed");
    }

    @Test
    void failedRenameLeavesTheOldFileIntact() throws IOException {
        Path target = existing();
        AtomicFileWriter writer = new AtomicFileWriter((source, dest, options) -> {
            throw new IOException("simulated power loss during rename");
        });
        assertThrows(IOException.class, () -> writer.write(target, NEW));
        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertEquals(List.of(target), listing(dir));
    }

    @Test
    void usesAtomicMoveAndFallsBackWhenTheFileSystemCannot() throws IOException {
        Path target = existing();
        List<List<CopyOption>> calls = new ArrayList<>();
        AtomicFileWriter writer = new AtomicFileWriter((source, dest, options) -> {
            calls.add(Arrays.asList(options));
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), dest.toString(), "not here");
            }
            Files.move(source, dest, options);
        });
        writer.write(target, NEW);
        assertArrayEquals(NEW, Files.readAllBytes(target));
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).contains(StandardCopyOption.ATOMIC_MOVE));
        assertEquals(List.of(StandardCopyOption.REPLACE_EXISTING), calls.get(1));
    }

    @Test
    void retriesARenameThatIsBrieflyDenied() throws IOException {
        Path target = existing();
        int[] attempts = {0};
        AtomicFileWriter writer = new AtomicFileWriter((source, dest, options) -> {
            if (++attempts[0] < 3) {
                throw new AccessDeniedException(dest.toString());
            }
            Files.move(source, dest, options);
        });
        writer.write(target, NEW);
        assertEquals(3, attempts[0]);
        assertArrayEquals(NEW, Files.readAllBytes(target));
    }

    @Test
    void givesUpAfterRepeatedDenials() throws IOException {
        Path target = existing();
        AtomicFileWriter writer = new AtomicFileWriter((source, dest, options) -> {
            throw new AccessDeniedException(dest.toString());
        });
        assertThrows(AccessDeniedException.class, () -> writer.write(target, NEW));
        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertFalse(listing(dir).size() > 1);
    }

    private Path existing() throws IOException {
        Path target = dir.resolve("cloud.yml");
        Files.write(target, OLD);
        return target;
    }

    private static List<Path> listing(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }
}
