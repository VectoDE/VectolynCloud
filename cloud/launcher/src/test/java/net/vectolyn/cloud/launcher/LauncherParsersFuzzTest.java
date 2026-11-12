// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.launcher;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.List;

/**
 * Fuzz targets for the command line parser of the launcher and its language switch. They run before
 * anything else is loaded, so they may only fail with their own documented exception.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class LauncherParsersFuzzTest {

    @FuzzTest
    void argumentParserFailsOnlyWithArgumentException(@NotNull List<@NotNull String> args) {
        try {
            ArgumentParser.parse(args.toArray(new String[0]));
        } catch (ArgumentException expected) {
            // unknown, repeated or incomplete argument
        }
    }

    @FuzzTest
    void languageParseNeverThrows(@NotNull String value) {
        Language.parse(value);
    }
}
