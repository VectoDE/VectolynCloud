// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code privacy.yml} in the cloud home. Every field except {@code schema-version} is optional;
 * without the file the defaults apply. Checked by {@link PrivacyConfigLoader}.
 *
 * @param schemaVersion format version, {@value #SCHEMA_VERSION}
 * @param ipBans        IP ban list settings
 */
@JsonPropertyOrder({"schema-version", "ip-bans"})
public record PrivacyConfig(@JsonProperty(required = true) int schemaVersion, IpBans ipBans) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;

    /** Fills in the defaults. */
    public PrivacyConfig {
        ipBans = ipBans == null ? IpBans.defaults() : ipBans;
    }

    /** Configuration without a file. */
    public static PrivacyConfig defaults() {
        return new PrivacyConfig(SCHEMA_VERSION, null);
    }

    /**
     * {@code ip-bans}.
     *
     * @param retentionDays an entry is deleted this many days after it was created, 1 to
     *                      {@value #MAX_RETENTION_DAYS} (default {@value #DEFAULT_RETENTION_DAYS});
     *                      a ban that is still needed is renewed by banning the address again
     */
    @JsonPropertyOrder({"retention-days"})
    public record IpBans(Integer retentionDays) {

        /** Default retention period in days. */
        public static final int DEFAULT_RETENTION_DAYS = 90;

        /** Longest retention period in days. */
        public static final int MAX_RETENTION_DAYS = 3650;

        /** Fills in the defaults. */
        public IpBans {
            retentionDays = retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays;
        }

        /** The defaults. */
        public static IpBans defaults() {
            return new IpBans(null);
        }
    }
}
