package net.vectora.cloud.monitor.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.monitor.alert.Severity;

/**
 * {@code monitor.yml} in the cloud home. Every field except {@code schema-version} is optional;
 * missing ones take the defaults below. Without the file the monitor runs with
 * {@link #defaults()}: sampling on, the {@linkplain #defaultRules() default rules}, history in
 * memory only, notifications and the Prometheus endpoint off. Checked by {@link MonitorConfigLoader}.
 *
 * @param schemaVersion  format version, {@value #SCHEMA_VERSION}
 * @param enabled        whether the monitor samples at all (default true)
 * @param sampleInterval time between samples, 1s to 5m (default 10s)
 * @param staleAfter     a status report older than this counts as missing, at least the sample
 *                       interval, at most 1h (default 60s: the server bridges report every
 *                       100 ticks, which takes longer than 30 s below about 3.3 TPS)
 * @param history        history settings
 * @param rules          alert rules; missing means the default rules, {@code []} means none
 * @param notifications  where alerts go besides the log
 * @param prometheus     optional metrics endpoint
 */
@JsonPropertyOrder({"schema-version", "enabled", "sample-interval", "stale-after", "history", "rules",
        "notifications", "prometheus"})
public record MonitorConfig(@JsonProperty(required = true) int schemaVersion, Boolean enabled, TimeSpan sampleInterval,
                            TimeSpan staleAfter, History history, List<Rule> rules, Notifications notifications,
                            Prometheus prometheus) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;

    /** Fills in the defaults. */
    public MonitorConfig {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        sampleInterval = sampleInterval == null ? TimeSpan.ofSeconds(10) : sampleInterval;
        staleAfter = staleAfter == null ? TimeSpan.ofSeconds(60) : staleAfter;
        history = history == null ? History.defaults() : history;
        rules = rules == null ? defaultRules() : List.copyOf(rules);
        notifications = notifications == null ? Notifications.defaults() : notifications;
        prometheus = prometheus == null ? Prometheus.defaults() : prometheus;
    }

    /** Configuration without a file. */
    public static MonitorConfig defaults() {
        return new MonitorConfig(SCHEMA_VERSION, null, null, null, null, null, null, null);
    }

    /**
     * The rules used when {@code rules} is missing: TPS below 18 for a minute, heap above 90 % for
     * a minute, less than 5 GB free in the home, three crashes within ten minutes, node memory
     * above 95 % for five minutes.
     *
     * @return the default rules
     */
    public static List<Rule> defaultRules() {
        return List.of(
                new Rule("low-tps", "tps < 18 for 60s clear at 19 after 30s", Severity.WARNING, null, null),
                new Rule("high-heap", "heap > 90% for 60s clear at 85%", Severity.WARNING, null, null),
                new Rule("low-disk", "disk-free < 5GB clear at 6GB", Severity.CRITICAL, null, null),
                new Rule("crash-loop", "crashes >= 3 within 10m", Severity.CRITICAL, null, null),
                new Rule("high-node-memory", "node-memory > 95% for 5m clear at 90%", Severity.WARNING, null, null));
    }

    /**
     * {@code history}.
     *
     * @param samples         samples kept per service and for the node, 10 to 100000 (default 360,
     *                        one hour at 10 s)
     * @param persist         whether the history is also written to {@code state/monitor-history.bin}
     *                        and read back after a restart (default false)
     * @param retention       samples older than this are dropped from the file, 1m to 7d (default 24h)
     * @param persistInterval how often the file is written, 1m to 1h (default 5m); always on shutdown
     */
    @JsonPropertyOrder({"samples", "persist", "retention", "persist-interval"})
    public record History(Integer samples, Boolean persist, TimeSpan retention, TimeSpan persistInterval) {

        /** Fills in the defaults. */
        public History {
            samples = samples == null ? 360 : samples;
            persist = persist == null ? Boolean.FALSE : persist;
            retention = retention == null ? TimeSpan.ofSeconds(24 * 3600) : retention;
            persistInterval = persistInterval == null ? TimeSpan.ofSeconds(300) : persistInterval;
        }

        /** The defaults. */
        public static History defaults() {
            return new History(null, null, null, null);
        }
    }

    /**
     * One entry of {@code rules}.
     *
     * @param name     unique name, {@code [a-z0-9][a-z0-9_-]{0,31}}
     * @param when     the expression, see {@code RuleParser}
     * @param severity {@code warning} (default) or {@code critical}
     * @param groups   only services of these groups; empty or missing for all
     * @param enabled  false keeps the rule in the file without using it (default true)
     */
    @JsonPropertyOrder({"name", "when", "severity", "groups", "enabled"})
    public record Rule(@JsonProperty(required = true) String name, @JsonProperty(required = true) String when,
                       Severity severity, List<String> groups, Boolean enabled) {

        /** Fills in the defaults. */
        public Rule {
            severity = severity == null ? Severity.WARNING : severity;
            groups = groups == null ? List.of() : List.copyOf(groups);
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }

    /**
     * {@code notifications}.
     *
     * @param webhook the generic webhook
     */
    public record Notifications(Webhook webhook) {

        /** Fills in the defaults. */
        public Notifications {
            webhook = webhook == null ? Webhook.defaults() : webhook;
        }

        /** The defaults: webhook off. */
        public static Notifications defaults() {
            return new Notifications(null);
        }
    }

    /**
     * {@code notifications.webhook}. The URL itself is a secret and lives in
     * {@code secrets/vectora.env}; this section only names the key.
     *
     * @param enabled      whether alerts are posted (default false)
     * @param urlSecret    key in {@code vectora.env} that holds the HTTPS URL
     *                     (default {@code VECTORA_MONITOR_WEBHOOK_URL})
     * @param format       body format (default {@code discord})
     * @param timeout      connect and response limit, 1s to 60s (default 10s)
     * @param sendResolved whether the end of an alert is posted too (default true)
     * @param minSeverity  alerts below this are not posted (default {@code warning})
     */
    @JsonPropertyOrder({"enabled", "url-secret", "format", "timeout", "send-resolved", "min-severity"})
    public record Webhook(Boolean enabled, String urlSecret, WebhookFormat format, TimeSpan timeout,
                          Boolean sendResolved, Severity minSeverity) {

        /** Default key of the URL in {@code vectora.env}. */
        public static final String DEFAULT_URL_SECRET = "VECTORA_MONITOR_WEBHOOK_URL";

        /** Fills in the defaults. */
        public Webhook {
            enabled = enabled == null ? Boolean.FALSE : enabled;
            urlSecret = urlSecret == null ? DEFAULT_URL_SECRET : urlSecret;
            format = format == null ? WebhookFormat.DISCORD : format;
            timeout = timeout == null ? TimeSpan.ofSeconds(10) : timeout;
            sendResolved = sendResolved == null ? Boolean.TRUE : sendResolved;
            minSeverity = minSeverity == null ? Severity.WARNING : minSeverity;
        }

        /** The defaults: off. */
        public static Webhook defaults() {
            return new Webhook(null, null, null, null, null, null);
        }
    }

    /** Body of a webhook post. */
    public enum WebhookFormat {
        /** {@code {"content": "...", "allowed_mentions": {"parse": []}}}, for a Discord channel webhook. */
        @JsonProperty("discord") DISCORD,
        /** {@code {"text": "..."}}, for Slack and Mattermost incoming webhooks. */
        @JsonProperty("slack") SLACK,
        /** A JSON object with every field of the alert, for your own receiver. */
        @JsonProperty("json") JSON
    }

    /**
     * {@code prometheus}: an HTTP endpoint with the metrics in the Prometheus text format.
     *
     * @param enabled whether it runs (default false)
     * @param bind    address to listen on; must be a loopback address (default {@code 127.0.0.1})
     * @param port    port, 1 to 65535 (default 9464)
     */
    @JsonPropertyOrder({"enabled", "bind", "port"})
    public record Prometheus(Boolean enabled, String bind, Integer port) {

        /** Fills in the defaults. */
        public Prometheus {
            enabled = enabled == null ? Boolean.FALSE : enabled;
            bind = bind == null ? "127.0.0.1" : bind;
            port = port == null ? 9464 : port;
        }

        /** The defaults: off. */
        public static Prometheus defaults() {
            return new Prometheus(null, null, null);
        }
    }
}
