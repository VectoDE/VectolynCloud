// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiResponse;
import net.vectolyn.cloud.panel.auth.AuditLog;
import net.vectolyn.cloud.panel.auth.PanelUsers;
import net.vectolyn.cloud.panel.config.PanelConfig;
import net.vectolyn.cloud.panel.config.PanelConfigLoader;

/**
 * Starts the panel server with the frontend from the jar on {@code 127.0.0.1}, for the browser
 * tests (Playwright, axe, Lighthouse) and for looking at the panel during development:
 * {@code ./gradlew :cloud:panel:panelPreview -Pvectolyn.panel.web=true}. Port from the first
 * argument (default 8090). Accounts live in a temporary folder; the setup token is printed. The
 * REST API of a node is not there: {@code /api/v1/...} answers with an empty list. Runs until the
 * process is stopped.
 */
@SuppressWarnings("FutureReturnValueIgnored")
public final class PanelPreview {

    private PanelPreview() {
    }

    /** A node without services and with one setup token. */
    private static final class PreviewNode implements NodeAccess {

        private final String token;
        private boolean used;

        PreviewNode(String token) {
            this.token = token;
        }

        @Override
        public synchronized <T> Optional<T> useSetupToken(String presented, ApiTokens.SetupUse<T> use) throws IOException {
            if (used || !token.equals(presented)) {
                return Optional.empty();
            }
            T result = use.apply("owner");
            used = true;
            return Optional.of(result);
        }

        @Override
        public ApiResponse call(ApiToken principal, String method, String target, byte[] body, String ifMatch)
                throws ApiException {
            if (method.equals("GET") && target.equals("/services")) {
                return ApiResponse.ok(java.util.List.of(
                        Map.of("id", "lobby-1", "group", "lobby", "task", "lobby", "index", 1, "mode", "static",
                                "state", "running", "port", 30001, "restart-attempts", 0, "players-online", 3,
                                "max-players", 50),
                        Map.of("id", "survival-1", "group", "survival", "task", "survival", "index", 1, "mode", "dynamic",
                                "state", "starting", "port", 30002, "restart-attempts", 0)));
            }
            if (method.equals("GET") && target.equals("/tasks")) {
                return ApiResponse.ok(Map.of("lobby", Map.of(), "survival", Map.of()));
            }
            return ApiResponse.ok(Map.of("items", java.util.List.of()));
        }

        @Override
        public java.util.Set<String> subscribe(io.netty.channel.Channel channel, ApiToken principal,
                                               java.util.Set<String> types, java.util.Set<String> services) {
            // One made-up output line a second, so the console has something to show.
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            var task = channel.eventLoop().scheduleAtFixedRate(() -> channel.writeAndFlush(
                    new io.netty.handler.codec.http.websocketx.TextWebSocketFrame("{\"type\":\"service-output\","
                            + "\"time\":\"2026-11-12T10:00:00Z\",\"data\":{\"service\":\"lobby-1\","
                            + "\"line\":\"[preview] line " + n.incrementAndGet() + "\"}}")), 1, 1,
                    java.util.concurrent.TimeUnit.SECONDS);
            channel.closeFuture().addListener(f -> task.cancel(false));
            return types.isEmpty() ? java.util.Set.of("service-state", "service-output") : types;
        }
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
        Path home = Files.createTempDirectory("vectolyn-panel-preview");
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String token = "vcs_preview" + HexFormat.of().formatHex(random);
        PanelApi api = new PanelApi(PanelUsers.load(home.resolve("secrets").resolve(PanelUsers.FILE_NAME)),
                new AuditLog(home.resolve("logs").resolve(AuditLog.FILE_NAME), Clock.systemUTC()),
                new PreviewNode(token), null, Clock.systemUTC(), System::nanoTime,
                new net.vectolyn.cloud.panel.files.ServiceFiles(home));
        Path lobby = Files.createDirectories(home.resolve("temp/services/lobby-1/plugins"));
        Files.writeString(lobby.getParent().resolve("server.properties"), "motd=Preview\nmax-players=50\n");
        Files.writeString(lobby.resolve("config.yml"), "greeting: hello\n");
        PanelServer server = PanelServer.builder(settings).version("preview").api(api).build();
        InetSocketAddress address = server.start();
        System.out.println("Panel preview on http://localhost:" + address.getPort() + "/");
        System.out.println("Setup token (preview only): " + token);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
    }
}
