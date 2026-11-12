// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import net.vectolyn.cloud.panel.config.PanelConfig;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;

/**
 * Starts the panel server with the frontend from the jar on {@code 127.0.0.1}, for the browser
 * tests (Playwright, axe, Lighthouse) and for looking at the panel during development:
 * {@code ./gradlew :cloud:panel:panelPreview -Pvectolyn.panel.web=true}. Port from the first
 * argument (default 8090). Runs until the process is stopped.
 */
public final class PanelPreview {

    private PanelPreview() {
    }

    /**
     * Starts the preview.
     *
     * @param args optional port
     * @throws Exception if the server cannot start
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PanelConfig.DEFAULT_PORT;
        PanelConfigLoader.Settings settings = PanelConfigLoader.check(new PanelConfig(1, true, "127.0.0.1", port, null));
        PanelServer server = PanelServer.builder(settings).version("preview").build();
        InetSocketAddress address = server.start();
        System.out.println("Panel preview on http://127.0.0.1:" + address.getPort() + "/");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
    }
}
