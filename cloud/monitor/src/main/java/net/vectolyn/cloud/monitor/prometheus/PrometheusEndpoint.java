package net.vectolyn.cloud.monitor.prometheus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Optional HTTP endpoint {@code GET /metrics} with {@link PrometheusFormat}. Off by default
 * ({@code prometheus.enabled}) and only on a loopback address: a Prometheus or agent on the same
 * machine scrapes it, or an operator forwards it through SSH. There is no authentication; the
 * metrics contain no secrets, but service names and load figures are still nobody else's business.
 *
 * <ul>
 *   <li>Refuses to bind to anything but a loopback address.</li>
 *   <li>Accepts only {@code Host} headers naming the loopback ({@code localhost}, {@code 127.x.x.x},
 *       {@code [::1]}, with or without port), so a web page cannot read it through DNS rebinding.</li>
 *   <li>{@code GET} and {@code HEAD} on {@code /metrics} only; anything else is 404 or 405.</li>
 * </ul>
 *
 * Uses the JDK's {@code jdk.httpserver} module, so no further library is needed. The API (PR 15)
 * may take over the path on its own server and drop this class.
 */
public final class PrometheusEndpoint implements AutoCloseable {

    /** Path of the metrics. */
    public static final String PATH = "/metrics";

    private static final Pattern LOOPBACK_HOST = Pattern.compile(
            "(localhost|127(\\.[0-9]{1,3}){3}|\\[::1\\])(:[0-9]{1,5})?", Pattern.CASE_INSENSITIVE);

    private final HttpServer server;
    private final ExecutorService executor;

    private PrometheusEndpoint(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts the endpoint.
     *
     * @param bind loopback address
     * @param port port, 0 for any free one (tests)
     * @param body supplies the text for each request
     * @return the running endpoint
     * @throws IOException if the port cannot be bound
     * @throws IllegalArgumentException if {@code bind} is not a loopback address
     */
    public static PrometheusEndpoint start(InetAddress bind, int port, Supplier<String> body) throws IOException {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(body, "body");
        if (!bind.isLoopbackAddress()) {
            throw new IllegalArgumentException("the Prometheus endpoint only listens on a loopback address");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vectolyn-monitor-prometheus");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, body));
        server.start();
        return new PrometheusEndpoint(server, executor);
    }

    private static void handle(HttpExchange exchange, Supplier<String> body) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || !LOOPBACK_HOST.matcher(host).matches()) {
                exchange.sendResponseHeaders(421, -1);
                return;
            }
            if (!exchange.getRequestURI().getRawPath().equals(PATH)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", PrometheusFormat.CONTENT_TYPE);
            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (RuntimeException e) {
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    /** The bound address. */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    /** Stops the endpoint. */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
