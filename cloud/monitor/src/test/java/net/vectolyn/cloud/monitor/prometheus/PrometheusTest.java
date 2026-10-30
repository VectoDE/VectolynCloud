package net.vectolyn.cloud.monitor.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import net.vectolyn.cloud.monitor.Fakes;
import net.vectolyn.cloud.monitor.Monitor;
import net.vectolyn.cloud.monitor.ServicePhase;
import net.vectolyn.cloud.monitor.config.MonitorConfigLoader;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ServiceStatus;
import org.junit.jupiter.api.Test;

class PrometheusTest {

    private static Monitor monitor(Fakes.Directory directory) throws Exception {
        return Monitor.builder(directory, new Fakes.Source(), MonitorConfigLoader.parse(
                        "schema-version: 1\nrules:\n  - name: low-tps\n    when: tps < 18\n    severity: critical\n"
                                .getBytes(StandardCharsets.UTF_8)))
                .clock(new Fakes.MutableClock(Instant.parse("2026-10-29T12:00:00Z"))).build();
    }

    @Test
    void rendersNodeServicesAndAlerts() throws Exception {
        Fakes.Directory directory = new Fakes.Directory().running("lobby-1", "lobby", 100)
                .put("proxy-1", "proxy", true, ServicePhase.RUNNING, 200, 0)
                .put("survival-1", "survival", false, ServicePhase.CRASHED, 0, 3);
        try (Monitor monitor = monitor(directory)) {
            monitor.statusReported(new ServiceStatus(new ServiceId("lobby-1"), 3, 100, 12.5, 40, 512, 1024));
            monitor.statusReported(new ServiceStatus(new ServiceId("proxy-1"), 4, 500, 20, 0, 512, -1));
            monitor.sampleNow();
            String text = PrometheusFormat.render(monitor);
            assertTrue(text.contains("# TYPE vectolyn_node_cpu_percent gauge\nvectolyn_node_cpu_percent 10\n"), text);
            assertTrue(text.contains("vectolyn_node_disk_free_bytes 107374182400\n"), text);
            assertTrue(text.contains("vectolyn_service_tps{service=\"lobby-1\",group=\"lobby\"} 12.500\n"), text);
            assertTrue(text.contains("vectolyn_service_players{service=\"proxy-1\",group=\"proxy\"} 4\n"), text);
            assertFalse(text.contains("vectolyn_service_tps{service=\"proxy-1\""), "no TPS for proxies");
            assertFalse(text.contains("vectolyn_service_heap_max_bytes{service=\"proxy-1\""), "unknown is left out, not 0");
            assertFalse(text.contains("vectolyn_service_rss_bytes"), "the fake source knows no process memory");
            assertTrue(text.contains("vectolyn_service_running{service=\"survival-1\",group=\"survival\"} 0\n"), text);
            assertTrue(text.contains("vectolyn_service_restart_attempts{service=\"survival-1\",group=\"survival\"} 3\n"));
            assertTrue(text.contains("# TYPE vectolyn_service_crashes_total counter\n"));
            assertTrue(text.contains("vectolyn_alerts_active{severity=\"critical\"} 1\n"), text);
            assertTrue(text.contains("vectolyn_alerts_active{severity=\"warning\"} 0\n"), text);
            assertTrue(text.endsWith("\n"));
            for (String line : text.split("\n")) {
                assertTrue(line.startsWith("# ") || line.matches("[a-z_]+(\\{[^}]*\\})? [0-9.]+"), line);
            }
        }
    }

    @Test
    void escapesLabels() {
        assertEquals("a\\\\b\\\"c\\nd", PrometheusFormat.escape("a\\b\"c\nd"));
        assertEquals("12", PrometheusFormat.format(12.0));
        assertEquals("0.125", PrometheusFormat.format(0.125));
    }

    private static String http(int port, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void endpointServesOnlyMetricsToLoopbackHosts() throws Exception {
        try (PrometheusEndpoint endpoint = PrometheusEndpoint.start(InetAddress.getLoopbackAddress(), 0,
                () -> "vectolyn_test 1\n")) {
            int port = endpoint.address().getPort();
            String ok = http(port, "GET /metrics HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n");
            assertTrue(ok.startsWith("HTTP/1.1 200"), ok);
            assertTrue(ok.contains("text/plain; version=0.0.4; charset=utf-8"), ok);
            assertTrue(ok.contains("X-content-type-options: nosniff") || ok.contains("X-Content-Type-Options: nosniff"), ok);
            assertTrue(ok.endsWith("vectolyn_test 1\n"), ok);
            assertTrue(http(port, "GET /metrics HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 200"));
            assertTrue(http(port, "GET /metrics HTTP/1.1\r\nHost: evil.example:" + port + "\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 421"), "DNS rebinding: foreign Host header");
            assertTrue(http(port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 404"));
            assertTrue(http(port, "GET /metrics/../x HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .startsWith("HTTP/1.1 404"));
            String post = http(port, "POST /metrics HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            assertTrue(post.startsWith("HTTP/1.1 405"), post);
            String head = http(port, "HEAD /metrics HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(head.startsWith("HTTP/1.1 200") && !head.contains("vectolyn_test"), head);
        }
    }

    @Test
    void endpointRefusesOtherAddresses() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PrometheusEndpoint.start(InetAddress.getByName("0.0.0.0"), 0, () -> ""));
    }
}
