// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigException;

/**
 * Everything data protection needs, put together from {@code privacy.yml}: the {@link IpBanStore}
 * and the hourly deletion of entries that are due. The node calls {@link #open}, {@link #start}
 * and {@link #close}.
 */
public final class PrivacyRuntime implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PrivacyRuntime.class.getName());

    private final PrivacyConfig config;
    private final IpBanStore ipBans;
    private ScheduledExecutorService scheduler;

    private PrivacyRuntime(PrivacyConfig config, IpBanStore ipBans) {
        this.config = config;
        this.ipBans = ipBans;
    }

    /**
     * Reads {@code privacy.yml} and opens the ban list; entries that are due are deleted.
     *
     * @param home  cloud home
     * @param clock time source
     * @return the runtime
     * @throws ConfigException            if {@code privacy.yml} is invalid
     * @throws IOException                if the ban list cannot be read or written
     * @throws IpBanStore.FormatException if the ban list is damaged
     */
    public static PrivacyRuntime open(Path home, Clock clock)
            throws ConfigException, IOException, IpBanStore.FormatException {
        PrivacyConfig config = PrivacyConfigLoader.load(home);
        IpBanStore store = IpBanStore.open(CloudHome.of(home).stateDir().resolve("ip-bans.json"),
                config.ipBans().retentionDays(), Objects.requireNonNull(clock, "clock"));
        return new PrivacyRuntime(config, store);
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

    /** Starts the hourly deletion. Calling it twice has no effect. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "privacy-purge");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int removed = ipBans.purge();
                if (removed > 0) {
                    LOG.log(Level.INFO, "Deleted {0} IP ban entries that were due", removed);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete due IP ban entries", e);
            }
        }, 1, 60, TimeUnit.MINUTES);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
