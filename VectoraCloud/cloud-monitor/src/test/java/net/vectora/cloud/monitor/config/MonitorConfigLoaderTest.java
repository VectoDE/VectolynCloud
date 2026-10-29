package net.vectora.cloud.monitor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.monitor.alert.AlertRule;
import net.vectora.cloud.monitor.alert.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorConfigLoaderTest {

    private static MonitorConfigLoader.Settings parse(String yaml) throws ConfigException {
        return MonitorConfigLoader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static List<ConfigError> errors(String yaml) {
        return assertThrows(ConfigException.class, () -> parse(yaml)).errors();
    }

    @Test
    void missingFileGivesDefaults(@TempDir Path home) throws Exception {
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.load(home);
        MonitorConfig config = settings.config();
        assertTrue(config.enabled());
        assertEquals(10, config.sampleInterval().duration().toSeconds());
        // The server bridges report every 100 ticks: 30 s at 3.3 TPS. 60 s keeps lagging servers measured.
        assertEquals(60, config.staleAfter().duration().toSeconds());
        assertFalse(config.notifications().webhook().enabled(), "notifications are off by default");
        assertFalse(config.prometheus().enabled(), "prometheus is off by default");
        assertFalse(config.history().persist());
        assertEquals(List.of("low-tps", "high-heap", "low-disk", "crash-loop", "high-node-memory"),
                settings.rules().stream().map(AlertRule::name).toList());
        assertTrue(settings.prometheusBind().isLoopbackAddress());
    }

    @Test
    void readsAFullFile(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("monitor.yml"), """
                schema-version: 1
                sample-interval: 5s
                stale-after: 20s
                history:
                  samples: 720
                  persist: true
                  retention: 12h
                  persist-interval: 10m
                rules:
                  - name: survival-tps
                    when: tps < 15 for 2m clear at 18
                    severity: critical
                    groups: [survival]
                  - name: off
                    when: heap > 99%
                    enabled: false
                notifications:
                  webhook:
                    enabled: true
                    url-secret: MY_DISCORD_WEBHOOK
                    format: slack
                    timeout: 5s
                    send-resolved: false
                    min-severity: critical
                prometheus:
                  enabled: true
                  bind: "::1"
                  port: 9100
                """);
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.load(home);
        MonitorConfig c = settings.config();
        assertEquals(5, c.sampleInterval().duration().toSeconds());
        assertEquals(720, c.history().samples());
        assertEquals(1, settings.rules().size(), "disabled rules are left out");
        AlertRule rule = settings.rules().get(0);
        assertEquals(Severity.CRITICAL, rule.severity());
        assertEquals(Set.of("survival"), rule.groups());
        MonitorConfig.Webhook webhook = c.notifications().webhook();
        assertEquals("MY_DISCORD_WEBHOOK", webhook.urlSecret());
        assertEquals(MonitorConfig.WebhookFormat.SLACK, webhook.format());
        assertFalse(webhook.sendResolved());
        assertTrue(settings.prometheusBind().isLoopbackAddress());
        assertEquals(9100, c.prometheus().port());
    }

    @Test
    void emptyRuleListMeansNoRules() throws Exception {
        assertTrue(parse("schema-version: 1\nrules: []\n").rules().isEmpty());
    }

    @Test
    void ruleErrorsNameThePathAndNotTheValue() {
        List<ConfigError> errors = errors("""
                schema-version: 1
                rules:
                  - name: ok
                    when: tps < 18
                  - name: broken
                    when: tps < s3cret-value
                  - name: ok
                    when: tps < 18
                  - name: Bad
                    when: tps < 18
                    groups: [Lobby]
                """);
        assertEquals(List.of("rules[1].when", "rules[2].name", "rules[3].name", "rules[3].groups[0]"),
                errors.stream().map(ConfigError::path).toList());
        assertEquals(ErrorCode.DUPLICATE_ENTRY, errors.get(1).code());
        for (ConfigError e : errors) {
            assertFalse(e.describe(Language.EN).contains("s3cret"), e.describe(Language.EN));
        }
    }

    @Test
    void rangesAreChecked() {
        List<String> paths = errors("""
                schema-version: 1
                sample-interval: 0s
                stale-after: 2h
                history:
                  samples: 5
                  retention: 30s
                  persist-interval: 2h
                notifications:
                  webhook:
                    url-secret: lower-case
                    timeout: 61s
                prometheus:
                  port: 0
                """).stream().map(ConfigError::path).toList();
        assertEquals(List.of("sample-interval", "stale-after", "history.samples", "history.retention",
                "history.persist-interval", "notifications.webhook.url-secret", "notifications.webhook.timeout",
                "prometheus.port"), paths);
    }

    @Test
    void boundaryValuesAreAccepted() throws Exception {
        parse("""
                schema-version: 1
                sample-interval: 1s
                stale-after: 1s
                history:
                  samples: 10
                  retention: 1m
                  persist-interval: 1h
                notifications:
                  webhook:
                    timeout: 60s
                prometheus:
                  port: 65535
                """);
        parse("schema-version: 1\nsample-interval: 5m\nstale-after: 1h\nhistory:\n  samples: 100000\n  retention: 7d\n"
                .replace("7d", "168h"));
    }

    @Test
    void staleAfterMustCoverTheSampleInterval() {
        assertEquals("stale-after", errors("schema-version: 1\nsample-interval: 60s\nstale-after: 30s\n").get(0).path());
    }

    @Test
    void prometheusOnlyBindsToLoopbackWithoutDnsLookups() throws Exception {
        assertEquals("prometheus.bind", errors("schema-version: 1\nprometheus:\n  bind: 0.0.0.0\n").get(0).path());
        assertEquals("prometheus.bind", errors("schema-version: 1\nprometheus:\n  bind: 192.168.1.10\n").get(0).path());
        assertEquals("prometheus.bind", errors("schema-version: 1\nprometheus:\n  bind: example.com\n").get(0).path());
        assertEquals("prometheus.bind", errors("schema-version: 1\nprometheus:\n  bind: 300.0.0.1\n").get(0).path());
        assertEquals("prometheus.bind", errors("schema-version: 1\nprometheus:\n  bind: \"::\"\n").get(0).path());
        assertTrue(parse("schema-version: 1\nprometheus:\n  bind: 127.0.0.2\n").prometheusBind().isLoopbackAddress());
        assertTrue(parse("schema-version: 1\nprometheus:\n  bind: localhost\n").prometheusBind().isLoopbackAddress());
        assertTrue(MonitorConfigLoader.loopback("not:an:address::::x") == null);
    }

    @Test
    void strictReaderRejectsUnknownFieldsAndMissingVersion() {
        assertEquals(ErrorCode.UNKNOWN_FIELD, errors("schema-version: 1\nwebhook-url: https://x\n").get(0).code());
        assertEquals(ErrorCode.SCHEMA_VERSION_MISSING, errors("enabled: true\n").get(0).code());
        assertEquals(ErrorCode.SCHEMA_VERSION_UNSUPPORTED, errors("schema-version: 2\n").get(0).code());
        assertEquals(ErrorCode.INVALID_VALUE, errors("schema-version: 1\nenabled: yes-please\n").get(0).code());
        assertEquals(ErrorCode.INVALID_VALUE,
                errors("schema-version: 1\nrules:\n  - name: x\n    when: tps < 18\n    severity: loud\n").get(0).code());
    }

    @Test
    void aLinkedFileIsRefused(@TempDir Path home) throws Exception {
        Path target = home.resolve("real.yml");
        Files.writeString(target, "schema-version: 1\n");
        try {
            Files.createSymbolicLink(home.resolve("monitor.yml"), target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here");
        }
        assertThrows(ConfigException.class, () -> MonitorConfigLoader.load(home));
    }
}
