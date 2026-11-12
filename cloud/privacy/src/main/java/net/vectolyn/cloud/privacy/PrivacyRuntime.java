// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigException;
import org.jspecify.annotations.Nullable;

/**
 * Everything data protection needs, put together from {@code privacy.yml}: the {@link IpBanStore}
 * and the hourly deletion of entries that are due. The node calls {@link #open}, {@link #start}
 * and {@link #close}.
 */
public final class PrivacyRuntime implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PrivacyRuntime.class.getName());

    private final PrivacyConfig config;
    private final IpBanStore ipBans;
    private final StatisticsCollector statistics;
    private final Telemetry telemetry;
    private final TrackingStore tracking;
    private final PrivacyRequests requests;
    private @Nullable ScheduledExecutorService scheduler;

    private PrivacyRuntime(PrivacyConfig config, IpBanStore ipBans, StatisticsCollector statistics,
                           Telemetry telemetry, TrackingStore tracking) {
        this.config = config;
        this.ipBans = ipBans;
        this.statistics = statistics;
        this.telemetry = telemetry;
        this.tracking = tracking;
        this.requests = new PrivacyRequests(tracking, config.tracking());
    }

    /**
     * Reads {@code privacy.yml} and opens the ban list; entries that are due are deleted.
     *
     * @param home         cloud home
     * @param clock        time source
     * @param cloudVersion version of the cloud, for the telemetry report
     * @return the runtime
     * @throws ConfigException            if {@code privacy.yml} is invalid
     * @throws IOException                if the ban list cannot be read or written
     * @throws IpBanStore.FormatException if the ban list is damaged
     * @throws StatisticsCollector.FormatException if the statistics file is damaged
     * @throws TrackingStore.FormatException if the tracking file is damaged
     */
    public static PrivacyRuntime open(Path home, Clock clock, String cloudVersion)
            throws ConfigException, IOException, IpBanStore.FormatException, StatisticsCollector.FormatException,
            TrackingStore.FormatException {
        PrivacyConfig config = PrivacyConfigLoader.load(home);
        IpBanStore store = IpBanStore.open(CloudHome.of(home).stateDir().resolve("ip-bans.json"),
                config.ipBans().retentionDays(), Objects.requireNonNull(clock, "clock"));
        StatisticsCollector statistics = StatisticsCollector.open(
                CloudHome.of(home).stateDir().resolve("statistics.json"), clock);
        PrivacyConfig.Telemetry settings = config.telemetry();
        Telemetry telemetry = new Telemetry(settings, cloudVersion, System.getProperty("os.name", "unknown"),
                config.statistics().enabled() ? statistics::snapshot : List::of,
                settings.enabled() && settings.endpoint() != null
                        ? new HttpTelemetrySender(URI.create(settings.endpoint())) : body -> { });
        TrackingStore tracking = TrackingStore.open(CloudHome.of(home).stateDir().resolve("tracking.json"),
                config.tracking().retentionDays(), clock);
        return new PrivacyRuntime(config, store, statistics, telemetry, tracking);
    }

    /**
     * The checked configuration.
     *
     * @return the configuration
     */
    public PrivacyConfig config() {
        return config;
    }

    /**
     * The IP ban list.
     *
     * @return the store
     */
    public IpBanStore ipBans() {
        return ipBans;
    }

    /**
     * Statistics per game mode.
     *
     * @return the collector
     */
    public StatisticsCollector statistics() {
        return statistics;
    }

    /**
     * The telemetry, inactive unless the configuration holds the consent.
     *
     * @return the telemetry
     */
    public Telemetry telemetry() {
        return telemetry;
    }

    /**
     * The per-player tracking.
     *
     * @return the store
     */
    public TrackingStore tracking() {
        return tracking;
    }

    /**
     * Answers the {@code /privacy} requests of proxies.
     *
     * @return the handler
     */
    public PrivacyRequests requests() {
        return requests;
    }

    /**
     * A player joined the network or moved to a server of a game mode. The totals per mode count
     * it while {@code statistics.enabled} is true; the player's own record only if the player agreed.
     *
     * @param player player id
     * @param name   player name
     * @param mode   group of the server
     */
    public void playerArrived(UUID player, String name, String mode) {
        if (config.statistics().enabled()) {
            statistics.arrived(player, mode);
        }
        if (config.tracking().enabled()) {
            tracking.arrived(player, name, mode);
        }
    }

    /**
     * A player left the network. Does nothing while {@code statistics.enabled} is false.
     *
     * @param player player id
     */
    public void playerLeft(UUID player) {
        if (config.statistics().enabled()) {
            statistics.left(player);
        }
        if (config.tracking().enabled()) {
            tracking.left(player);
        }
    }

    /** Starts the hourly deletion. Calling it twice has no effect. */
    @SuppressWarnings("FutureReturnValueIgnored") // the scheduler is shut down in close(); every task catches its failures
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "privacy-purge");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int tracked = tracking.purge();
                if (tracked > 0) {
                    LOG.log(Level.INFO, "Deleted {0} tracking records that were due", tracked);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete due tracking records", e);
            }
            try {
                int removed = ipBans.purge();
                if (removed > 0) {
                    LOG.log(Level.INFO, "Deleted {0} IP ban entries that were due", removed);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete due IP ban entries", e);
            }
        }, 1, 60, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(() -> {
            if (!config.statistics().enabled()) {
                return;
            }
            try {
                statistics.save();
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save the statistics", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
        if (telemetry.active()) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    telemetry.send();
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not send the telemetry report: {0}", e.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 1, 24, TimeUnit.HOURS);
        }
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        tracking.closeAll();
        try {
            tracking.save();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save the tracking records", e);
        }
        if (config.statistics().enabled()) {
            statistics.closeAll();
            try {
                statistics.save();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not save the statistics", e);
            }
        }
    }
}
