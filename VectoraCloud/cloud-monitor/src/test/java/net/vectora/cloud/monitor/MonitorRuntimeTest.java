package net.vectora.cloud.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.secret.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorRuntimeTest {

    private static final String SECRET_PATH = "api/webhooks/1/Zx9tokenSECRETpart42";

    private static MonitorRuntime open(Path home) throws ConfigException {
        return MonitorRuntime.open(home, new Fakes.Directory(), new SecretStore(home.resolve("secrets").resolve("vectora.env")),
                UnaryOperator.identity(), "node-1", new Fakes.Source());
    }

    private static void webhookOn(Path home) throws Exception {
        Files.writeString(home.resolve("monitor.yml"),
                "schema-version: 1\nnotifications:\n  webhook:\n    enabled: true\n");
    }

    @Test
    void notificationsAreOffByDefault(@TempDir Path home) throws Exception {
        try (MonitorRuntime runtime = open(home)) {
            assertFalse(runtime.notifying());
            runtime.start();
            assertTrue(runtime.prometheus().isEmpty(), "Prometheus is off by default");
        }
    }

    @Test
    void enabledWebhookWithoutUrlWarnsAndRunsWithoutIt(@TempDir Path home) throws Exception {
        webhookOn(home);
        try (Fakes.LogCapture log = new Fakes.LogCapture(MonitorRuntime.class.getName());
             MonitorRuntime runtime = open(home)) {
            assertFalse(runtime.notifying());
            assertTrue(log.text().contains("VECTORA_MONITOR_WEBHOOK_URL is not set"), log.text());
        }
    }

    @Test
    void plainHttpUrlIsRefusedWithoutLoggingIt(@TempDir Path home) throws Exception {
        webhookOn(home);
        Files.createDirectories(home.resolve("secrets"));
        Files.writeString(home.resolve("secrets").resolve("vectora.env"),
                "VECTORA_MONITOR_WEBHOOK_URL=http://discord.com/" + SECRET_PATH + "\n");
        try (Fakes.LogCapture log = new Fakes.LogCapture(MonitorRuntime.class.getName());
             MonitorRuntime runtime = open(home)) {
            assertFalse(runtime.notifying());
            assertTrue(log.text().contains("must start with https://"), log.text());
            assertFalse(log.text().contains("Zx9token"), log.text());
            assertFalse(log.text().contains("discord.com"), log.text());
        }
    }

    @Test
    void httpsUrlFromTheSecretsFileSwitchesTheWebhookOn(@TempDir Path home) throws Exception {
        webhookOn(home);
        Files.createDirectories(home.resolve("secrets"));
        Files.writeString(home.resolve("secrets").resolve("vectora.env"),
                "VECTORA_MONITOR_WEBHOOK_URL=https://discord.com/" + SECRET_PATH + "\n");
        try (Fakes.LogCapture log = new Fakes.LogCapture("net.vectora.cloud.monitor");
             MonitorRuntime runtime = open(home)) {
            assertTrue(runtime.notifying());
            assertFalse(log.text().contains("Zx9token"), log.text());
        }
    }

    @Test
    void invalidMonitorFileFailsTheStart(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("monitor.yml"), "schema-version: 1\nrules:\n  - name: x\n    when: tps <\n");
        assertThrows(ConfigException.class, () -> open(home));
    }

    @Test
    void prometheusStartsOnLoopbackWhenSwitchedOn(@TempDir Path home) throws Exception {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        Files.writeString(home.resolve("monitor.yml"),
                "schema-version: 1\nprometheus:\n  enabled: true\n  port: " + port + "\n");
        try (MonitorRuntime runtime = open(home)) {
            runtime.start();
            assertTrue(runtime.prometheus().orElseThrow().address().getAddress().isLoopbackAddress());
        }
    }
}
