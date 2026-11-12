// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.panel.PanelServer;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;
import org.jspecify.annotations.Nullable;

/**
 * Starts the web panel (docs/cloud/panel.md) next to the API. A problem never stops the node: it
 * is logged and the node runs without the panel.
 */
public final class NodePanel {

    private static final System.Logger LOG = System.getLogger(NodePanel.class.getName());

    private NodePanel() {
    }

    /**
     * Reads {@code config/panel.yml} and starts the panel when it is switched on.
     *
     * @param home      the cloud home
     * @param apiServer the started API server, or null while the API is off or did not start
     * @param version   the version of the cloud
     * @return the running panel, or empty
     */
    public static Optional<PanelServer> start(Path home, @Nullable ApiServer apiServer, String version) {
        PanelConfigLoader.Settings settings;
        try {
            settings = PanelConfigLoader.load(home);
        } catch (ConfigException e) {
            LOG.log(System.Logger.Level.ERROR, "config/panel.yml is invalid; the node runs without the panel:\n{0}",
                    e.getMessage());
            return Optional.empty();
        }
        if (!settings.config().enabled()) {
            return Optional.empty();
        }
        if (apiServer == null) {
            LOG.log(System.Logger.Level.ERROR, "The panel is switched on in panel.yml, but it needs the API"
                    + " (api.enabled in cloud.yml); the node runs without the panel. See docs/cloud/panel.md.");
            return Optional.empty();
        }
        PanelServer panel = null;
        try {
            panel = PanelServer.builder(settings).tls(apiServer.tls()).version(version).build();
            InetSocketAddress address = panel.start();
            LOG.log(System.Logger.Level.INFO, "Panel listens on {0}:{1,number,#}", address.getHostString(),
                    address.getPort());
            return Optional.of(panel);
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.ERROR, "The panel cannot start ({0}); the node runs without it.", e.getMessage());
            if (panel != null) {
                panel.close();
            }
            return Optional.empty();
        }
    }
}
