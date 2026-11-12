// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.secret.EnvFormatException;
import net.vectolyn.cloud.config.secret.Secret;
import net.vectolyn.cloud.config.secret.SecretStore;
import net.vectolyn.cloud.panel.NodeAccess;
import net.vectolyn.cloud.panel.PanelApi;
import net.vectolyn.cloud.panel.PanelServer;
import net.vectolyn.cloud.panel.auth.AuditLog;
import net.vectolyn.cloud.panel.auth.OidcClient;
import net.vectolyn.cloud.panel.auth.PanelUsers;
import net.vectolyn.cloud.panel.config.PanelConfig;
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
     * @param secrets   the secret store, for the client secret of single sign-on
     * @return the running panel, or empty
     */
    public static Optional<PanelServer> start(Path home, @Nullable ApiServer apiServer, String version,
                                              SecretStore secrets) {
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
            PanelUsers users = PanelUsers.load(home.resolve(CloudHome.SECRETS_DIR).resolve(PanelUsers.FILE_NAME));
            AuditLog audit = new AuditLog(home.resolve(CloudHome.LOGS_DIR).resolve(AuditLog.FILE_NAME), Clock.systemUTC());
            OidcClient oidc = oidc(settings, secrets);
            PanelApi api = new PanelApi(users, audit, NodeAccess.of(apiServer), oidc, Clock.systemUTC(), System::nanoTime,
                    new net.vectolyn.cloud.panel.files.ServiceFiles(home));
            panel = PanelServer.builder(settings).tls(apiServer.tls()).version(version).api(api).build();
            InetSocketAddress address = panel.start();
            LOG.log(System.Logger.Level.INFO, "Panel listens on {0}:{1,number,#}", address.getHostString(),
                    address.getPort());
            if (users.empty()) {
                LOG.log(System.Logger.Level.INFO, "The panel has no account yet: open it and create the first one"
                        + " with the setup token{0}.", apiServer.setupTokenPending() ? " shown in this console"
                        : " (run \"api token setup\" for one)");
            }
            return Optional.of(panel);
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.ERROR, "The panel cannot start ({0}); the node runs without it.", e.getMessage());
            if (panel != null) {
                panel.close();
            }
            return Optional.empty();
        }
    }

    private static @Nullable OidcClient oidc(PanelConfigLoader.Settings settings, SecretStore secrets)
            throws IOException {
        PanelConfig.Oidc config = settings.config().oidc();
        if (config == null) {
            return null;
        }
        Optional<Secret> secret;
        try {
            secret = secrets.load().get(OIDC_SECRET);
        } catch (EnvFormatException e) {
            throw new IOException("secrets/vectolyn.env is malformed", e);
        }
        if (secret.isEmpty() || secret.get().isEmpty()) {
            throw new IOException("single sign-on is configured in panel.yml, but " + OIDC_SECRET
                    + " is missing in secrets/vectolyn.env");
        }
        return new OidcClient(config, secret.get(), settings.publicUrl().orElseThrow(), Clock.systemUTC());
    }

    /** Key of the single sign-on client secret in {@code secrets/vectolyn.env}. */
    public static final String OIDC_SECRET = "VECTOLYN_PANEL_OIDC_SECRET";
}
