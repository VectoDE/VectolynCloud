// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.launcher.libs;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz target for {@link LibraryIndex#parse(String)}. The index decides which files the launcher
 * unpacks, so a malformed or unsafe line must end in a {@link LibraryException} and nothing else.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class LibraryIndexFuzzTest {

    @FuzzTest
    void parseFailsOnlyWithLibraryException(@NotNull String text) {
        try {
            LibraryIndex.parse(text);
        } catch (LibraryException expected) {
            // a malformed line, an unsafe or duplicate file name
        }
    }
}
