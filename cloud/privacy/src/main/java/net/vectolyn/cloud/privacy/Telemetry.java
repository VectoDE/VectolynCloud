// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telemetry: once a day one small JSON report goes to the endpoint the operator configured, but
 * only if {@code telemetry.enabled} is true <em>and</em> the explicit consent is on record (the
 * configuration is refused otherwise, see {@link PrivacyConfigLoader}). Without consent
 * {@link #active()} is false, {@link #report()} is empty and {@link #send()} does nothing, so no
 * network connection is ever opened.
 *
 * <p>The report holds exactly: the report format version, the cloud version, the name of the
 * operating system, and per game mode the three totals of {@link ModeStatistics}. It holds no
 * address, host name, instance id, player name or UUID. The operator can see the exact bytes with
 * {@link #report()} before agreeing.
 */
public final class Telemetry {

    /** Format version of the report. */
    public static final int REPORT_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Delivers a report. */
    @FunctionalInterface
    public interface Sender {
        /**
         * Posts one report.
         *
         * @param body the JSON report
         * @throws IOException if it could not be delivered
         * @throws InterruptedException if the thread was interrupted
         */
        void post(byte[] body) throws IOException, InterruptedException;
    }

    private final PrivacyConfig.Telemetry config;
    private final String cloudVersion;
    private final String os;
    private final Supplier<List<ModeStatistics>> statistics;
    private final Sender sender;

    /**
     * Creates the telemetry.
     *
     * @param config       the checked telemetry section
     * @param cloudVersion version of the cloud
     * @param os           name of the operating system
     * @param statistics   where the per-mode totals come from
     * @param sender       how a report is delivered
     */
    public Telemetry(PrivacyConfig.Telemetry config, String cloudVersion, String os,
                     Supplier<List<ModeStatistics>> statistics, Sender sender) {
        this.config = Objects.requireNonNull(config, "config");
        this.cloudVersion = Objects.requireNonNull(cloudVersion, "cloudVersion");
        this.os = Objects.requireNonNull(os, "os");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /**
     * Whether reports are sent: enabled, consent on record and an endpoint.
     *
     * @return true if {@link #send()} delivers
     */
    public boolean active() {
        return config.enabled() && config.consent() != null && config.endpoint() != null;
    }

    /**
     * The report as it would be sent now.
     *
     * @return the JSON bytes, or empty if telemetry is not active
     */
    public Optional<byte[]> report() {
        if (!active()) {
            return Optional.empty();
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("report-version", REPORT_VERSION);
        root.put("cloud-version", cloudVersion);
        root.put("os", os);
        ArrayNode modes = root.putArray("modes");
        for (ModeStatistics m : statistics.get()) {
            ObjectNode node = modes.addObject();
            node.put("mode", m.mode());
            node.put("logins", m.logins());
            node.put("playtime-seconds", m.playtimeSeconds());
            node.put("peak-players", m.peakPlayers());
        }
        return Optional.of(JSON.writeValueAsBytes(root));
    }

    /**
     * Sends one report if telemetry is active.
     *
     * @return whether a report was delivered
     * @throws IOException          if delivery failed
     * @throws InterruptedException if the thread was interrupted
     */
    public boolean send() throws IOException, InterruptedException {
        Optional<byte[]> body = report();
        if (body.isEmpty()) {
            return false;
        }
        sender.post(body.get());
        return true;
    }
}
