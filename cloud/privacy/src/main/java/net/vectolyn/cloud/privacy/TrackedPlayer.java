// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything the tracking keeps about one player who agreed to it. This record is also what the
 * player gets as the answer to an access request.
 *
 * @param uuid            the player's id
 * @param name            name at the last visit: printable ASCII, no space at either end, at most 32 characters
 * @param consentAt       when the player agreed
 * @param noticeVersion   version of the notice ({@link TrackingNotice#VERSION}) the player agreed to
 * @param firstSeen       first visit after the consent
 * @param lastSeen        last visit; the retention period counts from here
 * @param logins          how often the player joined the network or moved to another game mode
 * @param playtimeSeconds seconds spent on the network after the consent
 * @param modes           the same two numbers per game mode, sorted by mode
 */
public record TrackedPlayer(UUID uuid, String name, Instant consentAt, int noticeVersion, Instant firstSeen,
                            Instant lastSeen, long logins, long playtimeSeconds, List<ModeTotals> modes) {

    /** Valid names. */
    public static final Pattern NAME = Pattern.compile("[\\x21-\\x7E]([\\x20-\\x7E]{0,30}[\\x21-\\x7E])?");

    /**
     * Totals of one game mode.
     *
     * @param mode            group name
     * @param logins          arrivals in this mode
     * @param playtimeSeconds seconds spent in this mode
     */
    public record ModeTotals(String mode, long logins, long playtimeSeconds) {

        /** Checks the values. */
        public ModeTotals {
            if (!ModeStatistics.MODE.matcher(Objects.requireNonNull(mode, "mode")).matches()) {
                throw new IllegalArgumentException("invalid mode name");
            }
            if (logins < 0 || playtimeSeconds < 0) {
                throw new IllegalArgumentException("negative value");
            }
        }
    }

    /** Checks the values. */
    public TrackedPlayer {
        Objects.requireNonNull(uuid, "uuid");
        if (!NAME.matcher(Objects.requireNonNull(name, "name")).matches()) {
            throw new IllegalArgumentException("invalid name");
        }
        Objects.requireNonNull(consentAt, "consentAt");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (logins < 0 || playtimeSeconds < 0 || noticeVersion < 1) {
            throw new IllegalArgumentException("invalid number");
        }
        modes = List.copyOf(modes);
    }
}
