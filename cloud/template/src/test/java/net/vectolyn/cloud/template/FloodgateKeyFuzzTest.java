// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.template;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for {@link GeneratedFiles#decodeFloodgateKey(String, String)}, which decodes the
 * Base64 key an operator writes into a template setting. It may only fail with a
 * {@link TemplateException}; a key it returns has the exact length.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class FloodgateKeyFuzzTest {

    @FuzzTest
    void decodeFailsOnlyWithTemplateException(@NotNull String base64) {
        try {
            assertEquals(GeneratedFiles.FLOODGATE_KEY_BYTES, GeneratedFiles.decodeFloodgateKey("key.pem", base64).length);
        } catch (TemplateException expected) {
            // not Base64, or not the right length
        }
    }
}
