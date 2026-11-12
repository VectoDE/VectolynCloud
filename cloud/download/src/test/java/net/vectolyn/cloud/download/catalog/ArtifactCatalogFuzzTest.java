// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.download.catalog;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.IOException;

/**
 * Fuzz target for {@link ArtifactCatalog#parse(String)}. The catalog is shipped with the cloud, but
 * the parser also reads other catalogs, so it may only fail with an {@link IOException}.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class ArtifactCatalogFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithIoException(@NotNull String yaml) {
        try {
            ArtifactCatalog.parse(yaml);
        } catch (IOException expected) {
            // invalid content is the normal answer to bad input
        }
    }
}
