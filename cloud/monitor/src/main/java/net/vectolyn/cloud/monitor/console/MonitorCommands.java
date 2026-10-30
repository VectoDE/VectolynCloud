package net.vectolyn.cloud.monitor.console;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;
import net.vectolyn.cloud.monitor.Monitor;
import net.vectolyn.cloud.monitor.ServicePhase;
import net.vectolyn.cloud.monitor.ServiceView;
import net.vectolyn.cloud.monitor.alert.Alert;
import net.vectolyn.cloud.monitor.metric.Metric;
import net.vectolyn.cloud.monitor.metric.MetricUnit;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.ServiceSample;

/**
 * Console commands of the monitor. Only the definitions: the node registers them in its console
 * ({@code console.commands().register(commands.monitor())} and {@code .register(commands.alerts())}).
 *
 * <table>
 *   <caption>Commands</caption>
 *   <tr><td>{@code monitor}</td><td>node values and one line per service</td></tr>
 *   <tr><td>{@code monitor <service> [metric]}</td><td>history of one metric as a text chart
 *       (default {@code tps}, on proxies {@code players})</td></tr>
 *   <tr><td>{@code alerts list}</td><td>active and recently ended alerts</td></tr>
 *   <tr><td>{@code alerts ack <number|all>}</td><td>acknowledges alerts</td></tr>
 * </table>
 *
 * Everything printed is built from validated ids, rule names and numbers.
 */
public final class MonitorCommands {

    /** Metrics {@code monitor <service> <metric>} can chart. */
    public static final List<Metric> CHARTABLE = List.of(Metric.TPS, Metric.MSPT, Metric.HEAP, Metric.HEAP_USED,
            Metric.PLAYERS, Metric.CPU, Metric.RSS);

    private static final int CHART_WIDTH = 60;
    private static final int CHART_HEIGHT = 8;

    private final Monitor monitor;
    private final DateTimeFormatter time;

    /**
     * Creates the commands.
     *
     * @param monitor the monitor
     * @param zone    time zone for the times shown (the node's, {@link ZoneId#systemDefault()})
     */
    public MonitorCommands(Monitor monitor, ZoneId zone) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone);
    }

    /**
     * Builds {@code monitor [service] [metric]}.
     *
     * @return the command
     */
    public Command monitor() {
        return Command.builder("monitor")
                .description(Text.of("Shows TPS, memory and players of every service, or the history of one.",
                        "Zeigt TPS, Speicher und Spieler aller Dienste oder den Verlauf eines Dienstes."))
                .argument(Argument.optional("service", Text.of("Service id, for example lobby-1",
                        "Dienst-ID, zum Beispiel lobby-1")).suggest(previous -> serviceIds()))
                .argument(Argument.optional("metric", Text.of("What to chart: tps, mspt, heap, heap-used, players, cpu, rss",
                        "Was gezeigt wird: tps, mspt, heap, heap-used, players, cpu, rss"))
                        .suggest(CHARTABLE.stream().map(Metric::id).toList()))
                .executes(context -> {
                    Optional<String> service = context.optional("service");
                    if (service.isEmpty()) {
                        overview(context.out());
                    } else {
                        chart(context.out(), service.get(), context.optional("metric"));
                    }
                })
                .build();
    }

    /**
     * Builds {@code alerts list|ack}.
     *
     * @return the command
     */
    public Command alerts() {
        return Command.builder("alerts")
                .description(Text.of("Lists and acknowledges monitoring alerts.", "Listet und bestätigt Warnungen des Monitorings."))
                .subcommand(Command.builder("list")
                        .description(Text.of("Shows active and recently ended alerts.",
                                "Zeigt aktive und kürzlich beendete Warnungen."))
                        .executes(context -> list(context.out())))
                .subcommand(Command.builder("ack")
                        .description(Text.of("Acknowledges an alert; it stays listed until it clears.",
                                "Bestätigt eine Warnung; sie bleibt gelistet, bis sie endet."))
                        .argument(Argument.required("alert", Text.of("Alert number, or all", "Nummer der Warnung oder all"))
                                .suggest(previous -> alertIds()))
                        .executes(context -> ack(context.out(), context.argument("alert"))))
                .build();
    }

    private List<String> serviceIds() {
        return monitor.services().stream().map(v -> v.service().id().value()).toList();
    }

    private List<String> alertIds() {
        List<String> ids = new ArrayList<>();
        ids.add("all");
        monitor.activeAlerts().stream().filter(a -> !a.acknowledged()).forEach(a -> ids.add(Integer.toString(a.id())));
        return ids;
    }

    // ---- monitor -------------------------------------------------------------------------------

    private void overview(ConsoleOutput out) {
        if (!monitor.config().enabled()) {
            out.warning(Text.of("Monitoring is switched off (enabled: false in monitor.yml).",
                    "Das Monitoring ist ausgeschaltet (enabled: false in monitor.yml)."));
            return;
        }
        Optional<NodeSample> node = monitor.node();
        if (node.isPresent()) {
            NodeSample n = node.get();
            String memory = n.memoryTotal() > 0 && n.memoryUsed() >= 0
                    ? MetricUnit.bytes(n.memoryUsed()) + " / " + MetricUnit.bytes(n.memoryTotal()) + " ("
                    + MetricUnit.PERCENT.format(n.value(Metric.NODE_MEMORY).orElse(Double.NaN)) + ")" : "-";
            out.println(Text.of("Node: CPU %1$s, memory %2$s, free in home %3$s",
                            "Node: CPU %1$s, Speicher %2$s, frei im Home %3$s"),
                    MetricUnit.PERCENT.format(n.cpu()), memory, MetricUnit.bytes(n.diskFree()));
        } else {
            out.println(Text.of("Node: no sample yet.", "Node: noch keine Messung."));
        }
        List<ServiceView> services = monitor.services();
        if (services.isEmpty()) {
            out.println(Text.of("No services.", "Keine Dienste."));
        } else {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"service", "state", "players", "tps", "mspt", "heap", "cpu", "rss", "restarts", "crashes"});
            for (ServiceView view : services) {
                ServicePhase phase = view.service().phase();
                boolean live = phase == ServicePhase.STARTING || phase == ServicePhase.RUNNING
                        || phase == ServicePhase.STOPPING;
                ServiceSample s = live ? view.latest().orElse(null) : null;
                rows.add(new String[] {
                    view.service().id().value(),
                    view.service().phase().id(),
                    s == null || s.playersOnline() < 0 ? "-" : s.playersOnline() + "/" + Math.max(0, s.maxPlayers()),
                    s == null ? "-" : number(s.tps()),
                    s == null ? "-" : number(s.mspt()),
                    s == null || s.heapUsed() < 0 ? "-" : MetricUnit.bytes(s.heapUsed())
                            + (s.heapMax() > 0 ? " / " + MetricUnit.bytes(s.heapMax()) : ""),
                    s == null ? "-" : MetricUnit.PERCENT.format(s.cpu()),
                    s == null ? "-" : MetricUnit.bytes(s.rss()),
                    Integer.toString(view.service().restartAttempts()),
                    Integer.toString(view.crashes())});
            }
            out.heading(Text.of("Services", "Dienste").get(out.language()));
            for (String line : table(rows)) {
                out.println(line);
            }
        }
        int active = monitor.activeAlerts().size();
        if (active > 0) {
            out.warning(Text.of("%1$s alert(s) active, \"alerts list\" shows them.",
                    "%1$s Warnung(en) aktiv, „alerts list“ zeigt sie."), active);
        } else {
            out.success(Text.of("No active alerts.", "Keine aktiven Warnungen."));
        }
    }

    private void chart(ConsoleOutput out, String serviceText, Optional<String> metricText) throws CommandException {
        ServiceView view = monitor.services().stream()
                .filter(v -> v.service().id().value().equals(serviceText))
                .findFirst()
                .orElseThrow(() -> new CommandException(Text.of(
                        "Unknown service. \"monitor\" lists the services.",
                        "Unbekannter Dienst. „monitor“ listet die Dienste.")));
        Metric metric;
        if (metricText.isPresent()) {
            metric = Metric.byId(metricText.get()).filter(CHARTABLE::contains).orElseThrow(() -> new CommandException(
                    Text.of("Unknown metric. Choose one of %1$s.", "Unbekannte Metrik. Möglich sind %1$s."),
                    String.join(", ", CHARTABLE.stream().map(Metric::id).toList())));
        } else {
            metric = view.service().proxy() ? Metric.PLAYERS : Metric.TPS;
        }
        String id = view.service().id().value();
        if (metric.needsTickLoop() && view.service().proxy()) {
            out.warning(Text.of("%1$s is a proxy; it has no tick loop, so %2$s is not measured.",
                    "%1$s ist ein Proxy; er hat keine Tick-Schleife, %2$s wird nicht gemessen."), id, metric.id());
            return;
        }
        List<TextChart.Point> points = monitor.history(view.service().id()).stream()
                .map(s -> {
                    OptionalDouble v = s.value(metric);
                    return new TextChart.Point(s.at(), v.isPresent() ? v.getAsDouble() : Double.NaN);
                })
                .toList();
        if (points.stream().noneMatch(p -> Double.isFinite(p.value()))) {
            out.println(Text.of("No %1$s values for %2$s yet.", "Noch keine %1$s-Werte für %2$s."), metric.id(), id);
            return;
        }
        double ceiling = switch (metric) {
            case TPS -> 20;
            case HEAP, CPU -> 100;
            default -> 0;
        };
        out.heading(id + " " + metric.id() + " (" + time.format(points.get(0).at()) + " - "
                + time.format(points.get(points.size() - 1).at()) + ")");
        for (String line : TextChart.render(points, metric.unit(), 0, ceiling, CHART_WIDTH, CHART_HEIGHT)) {
            out.println(line);
        }
        out.println("  " + TextChart.summary(points, metric.unit()));
    }

    // ---- alerts --------------------------------------------------------------------------------

    private void list(ConsoleOutput out) {
        List<Alert> active = monitor.activeAlerts();
        List<Alert> ended = monitor.resolvedAlerts();
        if (active.isEmpty() && ended.isEmpty()) {
            out.success(Text.of("No alerts.", "Keine Warnungen."));
            return;
        }
        if (!active.isEmpty()) {
            out.heading(Text.of("Active alerts", "Aktive Warnungen").get(out.language()));
            List<String[]> rows = new ArrayList<>();
            for (Alert a : active) {
                rows.add(new String[] {"#" + a.id(), a.severity().id(), a.rule(), a.subject(),
                    time.format(a.firedAt()), shown(a), a.acknowledged()
                        ? Text.of("acknowledged", "bestätigt").get(out.language()) : ""});
            }
            for (String line : table(rows)) {
                out.println(line);
            }
        }
        if (!ended.isEmpty()) {
            out.heading(Text.of("Recently ended", "Kürzlich beendet").get(out.language()));
            List<String[]> rows = new ArrayList<>();
            List<Alert> recent = ended.subList(Math.max(0, ended.size() - 10), ended.size());
            for (Alert a : recent) {
                rows.add(new String[] {"#" + a.id(), a.severity().id(), a.rule(), a.subject(),
                    time.format(a.firedAt()) + " - " + a.resolvedAt().map(time::format).orElse("?"),
                    a.resolution().map(r -> r.name().toLowerCase(Locale.ROOT).replace('_', ' ')).orElse("")});
            }
            for (String line : table(rows)) {
                out.println(line);
            }
        }
    }

    private void ack(ConsoleOutput out, String which) throws CommandException {
        if (which.equals("all")) {
            int count = monitor.acknowledgeAll();
            out.success(Text.of("%1$s alert(s) acknowledged.", "%1$s Warnung(en) bestätigt."), count);
            return;
        }
        String digits = which.startsWith("#") ? which.substring(1) : which;
        int id;
        try {
            id = digits.matches("[1-9][0-9]{0,8}") ? Integer.parseInt(digits) : -1;
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0) {
            throw new CommandException(Text.of("Expected an alert number or all.", "Erwartet: Nummer einer Warnung oder all."));
        }
        if (!monitor.acknowledge(id)) {
            throw new CommandException(Text.of("No active, unacknowledged alert #%1$s.",
                    "Keine aktive, unbestätigte Warnung #%1$s."), id);
        }
        out.success(Text.of("Alert #%1$s acknowledged.", "Warnung #%1$s bestätigt."), id);
    }

    // ---- formatting ----------------------------------------------------------------------------

    private static String shown(Alert a) {
        return a.condition().metric().id() + " " + a.condition().metric().unit().format(a.value())
                + " (" + a.condition().canonical() + ")";
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    /** Pads the columns to their widest cell. */
    static List<String> table(List<String[]> rows) {
        int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        int[] width = new int[columns];
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                width[c] = Math.max(width[c], row[c].length());
            }
        }
        List<String> lines = new ArrayList<>();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder(" ");
            for (int c = 0; c < row.length; c++) {
                line.append(' ').append(row[c]);
                if (c < row.length - 1) {
                    line.append(" ".repeat(width[c] - row[c].length() + 1));
                }
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }
}
