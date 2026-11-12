// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.Language;

/**
 * Fuzz target for reading an imported archive: whatever the bytes are, {@link ConfigArchive#read}
 * either gives a plan or fails with {@link ConfigException}, and never writes a file.
 */
class ConfigArchiveFuzzTest {

    private static final ConfigService SERVICE;
    private static final byte[] CLOUD_BEFORE;

    static {
        try {
            Path home = Files.createTempDirectory("vectolyn-archive-fuzz");
            SERVICE = ConfigService.open(CloudHome.of(home));
            CLOUD_BEFORE = Files.readAllBytes(SERVICE.home().cloudFile());
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    @FuzzTest
    void readFailsOnlyWithConfigErrorsAndWritesNothing(byte @NotNull [] input) {
        ConfigArchive tool = new ConfigArchive(SERVICE, List.of(ConfigArchiveTest.MONITOR));
        try {
            tool.read(input);
        } catch (ConfigException expected) {
            for (ConfigError error : expected.errors()) {
                assertFalse(error.describe(Language.EN).isEmpty());
                assertFalse(error.describe(Language.DE).isEmpty());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            assertArrayEquals(CLOUD_BEFORE, Files.readAllBytes(SERVICE.home().cloudFile()));
            assertFalse(Files.exists(SERVICE.home().monitorFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
