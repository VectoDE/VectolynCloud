package net.vectora.cloud.monitor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.secret.EnvFormatException;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.cloud.config.secret.SecretStore;
import net.vectora.cloud.monitor.config.MonitorConfig;
import net.vectora.cloud.monitor.config.MonitorConfigLoader;
import net.vectora.cloud.monitor.console.MonitorCommands;
import net.vectora.cloud.monitor.history.HistoryStore;
import net.vectora.cloud.monitor.metric.MetricSource;
import net.vectora.cloud.monitor.metric.SystemMetricSource;
import net.vectora.cloud.monitor.notify.NotificationDispatcher;
import net.vectora.cloud.monitor.notify.Notifier;
import net.vectora.cloud.monitor.notify.WebhookNotifier;
import net.vectora.cloud.monitor.notify.WebhookUrl;
import net.vectora.cloud.monitor.prometheus.PrometheusEndpoint;
import net.vectora.cloud.monitor.prometheus.PrometheusFormat;

/**
 * Everything the monitor needs, put together from {@code monitor.yml}: the {@link Monitor} on
 * {@link SystemMetricSource}, the webhook (if switched on and its URL is in
 * {@code secrets/vectora.env}), the history file (if {@code history.persist}), the Prometheus
 * endpoint (if switched on) and the console commands. This is the one call the node makes:
 *
 * <pre>{@code
 * MonitorRuntime monitoring = MonitorRuntime.open(home, directory, secrets, RedactingFilter::redact, "node-1");
 * channelListener: case ServiceEvent.StatusReported r -> monitoring.monitor().statusReported(r.status());
 * serviceListener: stateChanged(info, previous) -> monitoring.monitor().serviceStateChanged(info.id(),
 *         ServicePhase.valueOf(info.state().name()), previous == null ? null : ServicePhase.valueOf(previous.name()));
 * console.commands().register(monitoring.commands().monitor());
 * console.commands().register(monitoring.commands().alerts());
 * monitoring.start();   // after the cloud channel, before the first service
 * monitoring.close();   // after the last service has stopped
 * }</pre>
 *
 * A webhook that is switched on but has no valid URL is logged as a warning and left out; the
 * rest of the monitor runs. An invalid {@code monitor.yml} fails {@link #open} with every problem.
 */
public final class MonitorRuntime implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MonitorRuntime.class.getName());

    private final MonitorConfigLoader.Settings settings;
    private final Monitor monitor;
    private final MonitorCommands commands;
    private final NotificationDispatcher dispatcher;
    private PrometheusEndpoint prometheus;
    private boolean closed;

    private MonitorRuntime(MonitorConfigLoader.Settings settings, Monitor monitor, NotificationDispatcher dispatcher) {
        this.settings = settings;
        this.monitor = monitor;
        this.dispatcher = dispatcher;
        this.commands = new MonitorCommands(monitor, ZoneId.systemDefault());
    }

    /**
     * Reads {@code monitor.yml} and builds the parts; nothing runs until {@link #start()}.
     *
     * @param home      cloud home
     * @param directory the node's services
     * @param secrets   {@code secrets/vectora.env}, for the webhook URL
     * @param redactor  log filter for secrets ({@code RedactingFilter::redact})
     * @param origin    name shown in notifications, for example the node id
     * @return the runtime
     * @throws ConfigException if {@code monitor.yml} is invalid
     */
    public static MonitorRuntime open(Path home, ServiceDirectory directory, SecretStore secrets,
                                      UnaryOperator<String> redactor, String origin) throws ConfigException {
        return open(home, directory, secrets, redactor, origin, SystemMetricSource.forHome(home));
    }

    /** Same with a given metric source (tests). */
    static MonitorRuntime open(Path home, ServiceDirectory directory, SecretStore secrets, UnaryOperator<String> redactor,
                               String origin, MetricSource source) throws ConfigException {
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(redactor, "redactor");
        Objects.requireNonNull(origin, "origin");
        MonitorConfigLoader.Settings settings = MonitorConfigLoader.load(home);
        MonitorConfig config = settings.config();
        List<Notifier> notifiers = new ArrayList<>();
        MonitorConfig.Webhook webhook = config.notifications().webhook();
        if (webhook.enabled()) {
            webhook(secrets, webhook, origin).ifPresent(notifiers::add);
        }
        NotificationDispatcher dispatcher = notifiers.isEmpty() ? null
                : new NotificationDispatcher(notifiers, webhook.minSeverity(), webhook.sendResolved(), redactor);
        Monitor.Builder builder = Monitor.builder(directory, source, settings);
        if (dispatcher != null) {
            builder.listener(dispatcher);
        }
        if (config.history().persist()) {
            builder.historyStore(new HistoryStore(home, config.history().retention().duration(), config.history().samples()));
        }
        return new MonitorRuntime(settings, builder.build(), dispatcher);
    }

    private static Optional<Notifier> webhook(SecretStore secrets, MonitorConfig.Webhook webhook, String origin) {
        Optional<Secret> url;
        try {
            url = secrets.load().get(webhook.urlSecret());
        } catch (IOException | EnvFormatException e) {
            LOG.warning("Webhook is switched on, but secrets/vectora.env could not be read; alerts go to the log only.");
            return Optional.empty();
        }
        if (url.isEmpty() || url.get().isEmpty()) {
            // Only the place to look is logged: the key named in monitor.yml, never data around it.
            LOG.warning("Webhook is switched on, but the key named in notifications.webhook.url-secret"
                    + " is not set in secrets/vectora.env; alerts go to the log only.");
            return Optional.empty();
        }
        try {
            WebhookUrl checked = WebhookUrl.of(url.get());
            return Optional.of(new WebhookNotifier(checked, webhook.format(), webhook.timeout().duration(), origin));
        } catch (IllegalArgumentException e) {
            // The message names the rule, never the URL.
            LOG.warning("Webhook is switched on, but its URL in secrets/vectora.env is not usable: " + e.getMessage()
                    + ". Alerts go to the log only.");
            return Optional.empty();
        }
    }

    /** The monitor. */
    public Monitor monitor() {
        return monitor;
    }

    /** The console commands, to register in the node's console. */
    public MonitorCommands commands() {
        return commands;
    }

    /** Whether a webhook is in use. */
    public boolean notifying() {
        return dispatcher != null;
    }

    /** The Prometheus endpoint while it runs. */
    public Optional<PrometheusEndpoint> prometheus() {
        return Optional.ofNullable(prometheus);
    }

    /**
     * Starts sampling and, if switched on, the Prometheus endpoint. A port that cannot be bound is
     * logged; sampling runs anyway.
     */
    public synchronized void start() {
        if (closed) {
            return;
        }
        monitor.start();
        MonitorConfig.Prometheus p = settings.config().prometheus();
        if (p.enabled() && prometheus == null && settings.config().enabled()) {
            try {
                prometheus = PrometheusEndpoint.start(settings.prometheusBind(), p.port(), () -> PrometheusFormat.render(monitor));
                LOG.info("Prometheus metrics on http://" + p.bind() + ":" + p.port() + PrometheusEndpoint.PATH);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(Level.WARNING, "Prometheus endpoint could not start on port " + p.port() + ": "
                        + e.getClass().getSimpleName());
            }
        }
    }

    /** Stops the endpoint, sampling and the notifications (queued ones are sent first). */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (prometheus != null) {
            prometheus.close();
        }
        monitor.close();
        if (dispatcher != null) {
            dispatcher.close();
        }
    }
}
