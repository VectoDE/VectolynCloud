package net.vectolyn.cloud.download.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for the lockfile parser: invalid content is reported as {@link LockFileException},
 * and whatever parses survives a write and read again unchanged.
 */
class SoftwareLockFileFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithLockFileException(@NotNull String input) throws LockFileException {
        SoftwareLock lock;
        try {
            lock = SoftwareLockFile.parse(input);
        } catch (LockFileException expected) {
            return;
        }
        assertEquals(lock, SoftwareLockFile.parse(SoftwareLockFile.format(lock)));
    }
}
