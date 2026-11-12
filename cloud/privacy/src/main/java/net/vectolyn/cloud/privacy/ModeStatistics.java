// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Totals of one game mode (the group of the server). No player is named: these are sums.
 *
 * @param mode            group name, {@code [a-z0-9][a-z0-9_-]{0,31}}
 * @param logins          how often a player arrived in this mode (joined the network there or switched to it)
 * @param playtimeSeconds seconds players spent in this mode, summed over all players
 * @param peakPlayers     most players in this mode at the same time since the statistics began
 */
public record ModeStatistics(String mode, long logins, long playtimeSeconds, int peakPlayers) {

    /** Valid mode names. */
    public static final Pattern MODE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /** Checks the values. */
    public ModeStatistics {
        Objects.requireNonNull(mode, "mode");
        if (!MODE.matcher(mode).matches()) {
            throw new IllegalArgumentException("invalid mode name");
        }
        if (logins < 0 || playtimeSeconds < 0 || peakPlayers < 0) {
            throw new IllegalArgumentException("negative value");
        }
    }
}
