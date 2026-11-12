// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the IP ban list.
 *
 * @param address   canonical IP address
 * @param reason    why, free text of at most {@value #MAX_REASON} characters; no personal data of the
 *                  person behind the address belongs here
 * @param createdAt when the entry was written; the retention period counts from here
 * @param expiresAt when the ban ends before the retention period does, or null for none
 */
public record IpBan(String address, String reason, Instant createdAt, Instant expiresAt) {

    /** Longest reason. */
    public static final int MAX_REASON = 200;

    /** Checks the values. */
    public IpBan {
        address = IpAddresses.normalize(address);
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("reason too long");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Whether the entry must not exist any more: the retention period or its own expiry has passed.
     *
     * @param now  the time to check at
     * @param days retention period in days
     * @return true if it is due for deletion
     */
    public boolean deletable(Instant now, int days) {
        return !createdAt.plus(Duration.ofDays(days)).isAfter(now) || (expiresAt != null && !expiresAt.isAfter(now));
    }
}
