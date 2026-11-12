// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/**
 * {@code privacy.yml} in the cloud home. Every field except {@code schema-version} is optional;
 * without the file the defaults apply. Checked by {@link PrivacyConfigLoader}.
 *
 * @param schemaVersion format version, {@value #SCHEMA_VERSION}
 * @param ipBans        IP ban list settings
 * @param statistics    statistics per game mode
 * @param telemetry     telemetry to a server of the operator's choice (off by default)
 */
@JsonPropertyOrder({"schema-version", "ip-bans", "statistics", "telemetry"})
public record PrivacyConfig(@JsonProperty(required = true) int schemaVersion, IpBans ipBans, Statistics statistics,
                            Telemetry telemetry) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;

    /** Fills in the defaults. */
    public PrivacyConfig {
        ipBans = ipBans == null ? IpBans.defaults() : ipBans;
        statistics = statistics == null ? Statistics.defaults() : statistics;
        telemetry = telemetry == null ? Telemetry.defaults() : telemetry;
    }

    /**
     * Configuration without a file.
     *
     * @return the defaults
     */
    public static PrivacyConfig defaults() {
        return new PrivacyConfig(SCHEMA_VERSION, IpBans.defaults(), Statistics.defaults(), Telemetry.defaults());
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

        /**
         * The defaults.
         *
         * @return default settings
         */
        public static IpBans defaults() {
            return new IpBans(DEFAULT_RETENTION_DAYS);
        }
    }

    /**
     * {@code statistics}: playtime, logins and player counts per game mode (the group of the
     * server). Totals only; no player is identified, and nothing leaves the machine.
     *
     * @param enabled whether the numbers are collected (default true)
     */
    @JsonPropertyOrder({"enabled"})
    public record Statistics(Boolean enabled) {

        /** Fills in the defaults. */
        public Statistics {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }

        /**
         * The defaults.
         *
         * @return default settings
         */
        public static Statistics defaults() {
            return new Statistics(Boolean.TRUE);
        }
    }

    /**
     * {@code telemetry}: off by default. It can only be switched on together with a record of who
     * agreed and when ({@code consent}), and it needs an endpoint; there is no default endpoint.
     *
     * @param enabled  whether a report is sent once a day (default false)
     * @param endpoint HTTPS URL that receives the report
     * @param consent  the explicit consent of the operator
     */
    @JsonPropertyOrder({"enabled", "endpoint", "consent"})
    public record Telemetry(Boolean enabled, @Nullable String endpoint, @Nullable Consent consent) {

        /** Fills in the defaults. */
        public Telemetry {
            enabled = enabled == null ? Boolean.FALSE : enabled;
        }

        /**
         * The defaults: off.
         *
         * @return default settings
         */
        public static Telemetry defaults() {
            return new Telemetry(Boolean.FALSE, null, null);
        }
    }

    /**
     * {@code telemetry.consent}.
     *
     * @param givenBy     who agreed (name of the administrator), at most 64 characters
     * @param givenAt     when, as an ISO-8601 instant such as {@code 2026-11-12T10:00:00Z}
     * @param textVersion version of the telemetry description that was shown, see
     *                    {@code docs/cloud/privacy.md} (current: {@value #CURRENT_TEXT_VERSION})
     */
    @JsonPropertyOrder({"given-by", "given-at", "text-version"})
    public record Consent(String givenBy, String givenAt, Integer textVersion) {

        /** Version of the telemetry description in the documentation. */
        public static final int CURRENT_TEXT_VERSION = 1;
    }
}
