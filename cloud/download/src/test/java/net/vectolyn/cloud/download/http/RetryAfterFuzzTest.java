// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.download.http;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Fuzz target for {@link DownloadClient#parseRetryAfter(String, Clock)}, which reads a header sent by
 * a remote server. It never throws and never returns a negative wait.
 * In a normal test run it executes in regression mode (stored inputs only); the nightly
 * workflow fuzzes it on Linux.
 */
class RetryAfterFuzzTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    @FuzzTest
    void parseRetryAfterNeverThrowsAndIsNeverNegative(@NotNull String header) {
        DownloadClient.parseRetryAfter(header, CLOCK).ifPresent(wait -> assertTrue(!wait.isNegative()));
    }
}
