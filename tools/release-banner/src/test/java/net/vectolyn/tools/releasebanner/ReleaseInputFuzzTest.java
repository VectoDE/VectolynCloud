// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.tools.releasebanner;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

/**
 * Fuzz targets for the release tag and the release date, which the release workflow reads from the
 * pushed tag name and a workflow input. Both may only fail with an {@link IllegalArgumentException}.
 * In a normal test run they execute in regression mode (stored inputs only); the nightly
 * workflow fuzzes them on Linux.
 */
class ReleaseInputFuzzTest {

    @FuzzTest
    void releaseVersionFailsOnlyWithIllegalArgument(@NotNull String tag) {
        try {
            ReleaseVersion.parse(tag);
        } catch (IllegalArgumentException expected) {
            // a tag that breaks a version rule
        }
    }

    @FuzzTest
    void releaseDateFailsOnlyWithIllegalArgument(@NotNull String iso) {
        try {
            ReleaseDate.parse(iso);
        } catch (IllegalArgumentException expected) {
            // not yyyy-MM-dd, or a date that does not exist
        }
    }
}
