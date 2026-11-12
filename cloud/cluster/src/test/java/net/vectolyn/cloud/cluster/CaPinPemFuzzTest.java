// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.IOException;

/**
 * Fuzz targets for the two cluster parsers that read text from outside: the CA pin a joining node is
 * given ({@link CaPin#parse(String)}) and the PEM files of the cluster ({@link Pem}).
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class CaPinPemFuzzTest {

    @FuzzTest
    void caPinNeverThrows(@NotNull String input) {
        CaPin.parse(input);
    }

    @FuzzTest
    void pemDecodeFailsOnlyWithIoException(byte @NotNull [] pem) {
        try {
            Pem.decode("CERTIFICATE", pem);
        } catch (IOException expected) {
            // no such block, or not Base64
        }
    }
}
