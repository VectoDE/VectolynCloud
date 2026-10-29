package net.vectora.cloud.backup.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import net.vectora.cloud.backup.manifest.BackupManifest.FileEntry;

/**
 * Fuzz target for {@link ManifestCodec#read(byte[])}, the reader of the untrusted
 * {@code manifest.json} inside a backup archive. In a normal test run it executes in regression
 * mode (stored inputs only); the nightly workflow fuzzes it on Linux.
 */
class ManifestCodecFuzzTest {

    @FuzzTest
    void readFailsOnlyWithManifestExceptionAndRoundTrips(byte @NotNull [] input) {
        BackupManifest manifest;
        try {
            manifest = ManifestCodec.read(input);
        } catch (ManifestException expected) {
            return;
        }
        byte[] written = ManifestCodec.write(manifest);
        try {
            BackupManifest again = ManifestCodec.read(written);
            assertEquals(manifest, again, "write must produce a manifest that reads back the same");
            assertArrayEquals(written, ManifestCodec.write(again));
        } catch (ManifestException e) {
            throw new AssertionError("written manifest does not read again: " + e.getMessage(), e);
        }
        for (FileEntry file : manifest.files()) {
            assertTrue(ManifestPaths.isSafe(file.path()), "unsafe path accepted");
            assertFalse(file.path().contains("..") && file.path().matches("(.*/)?\\.\\.(/.*)?"), "traversal accepted");
            assertFalse(file.path().startsWith("/") || file.path().contains("\\") || file.path().contains(":"),
                    "absolute or Windows-specific path accepted");
            assertTrue(file.size() >= 0 && file.sha256().length() == 64);
        }
    }
}
