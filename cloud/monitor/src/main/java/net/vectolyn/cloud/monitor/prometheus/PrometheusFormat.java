package net.vectolyn.cloud.monitor.prometheus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import net.vectolyn.cloud.monitor.Monitor;
import net.vectolyn.cloud.monitor.ServicePhase;
import net.vectolyn.cloud.monitor.ServiceView;
import net.vectolyn.cloud.monitor.alert.Alert;
import net.vectolyn.cloud.monitor.alert.Severity;
import net.vectolyn.cloud.monitor.metric.NodeSample;
import net.vectolyn.cloud.monitor.metric.ServiceSample;

/**
 * Renders the monitor's newest values in the Prometheus text exposition format (version 0.0.4).
 * Pure: no I/O, no server. {@link PrometheusEndpoint} serves it on loopback; the API (PR 15) can
 * serve the same text on its own HTTP stack with {@link #render(Monitor)} and {@link #CONTENT_TYPE}.
 *
 * <p>Values that were not measured are left out instead of being reported as 0.
 */
public final class PrometheusFormat {

    /** Content type of the output. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private PrometheusFormat() {
    }

    private record Series(String name, String type, String help, List<String> lines) {
    }

    /**
     * Renders the metrics.
     *
     * @param monitor the monitor
     * @return the text, ending with a line break
     */
    public static String render(Monitor monitor) {
        List<Series> all = new ArrayList<>();
        monitor.node().ifPresent(node -> nodeSeries(node, all));

        List<ServiceView> services = monitor.services();
        Series running = series(all, "vectolyn_service_running", "gauge", "1 while the service is running, 0 otherwise.");
        Series restarts = series(all, "vectolyn_service_restart_attempts", "gauge", "Restarts after crashes in a row.");
        Series crashes = series(all, "vectolyn_service_crashes_total", "counter", "Crashes since the cloud started.");
        Series players = series(all, "vectolyn_service_players", "gauge", "Players online.");
        Series maxPlayers = series(all, "vectolyn_service_max_players", "gauge", "Player limit.");
        Series tps = series(all, "vectolyn_service_tps", "gauge", "Ticks per second (game servers only).");
        Series mspt = series(all, "vectolyn_service_mspt", "gauge", "Milliseconds per tick (game servers only).");
        Series heapUsed = series(all, "vectolyn_service_heap_used_bytes", "gauge", "Used heap of the server JVM.");
        Series heapMax = series(all, "vectolyn_service_heap_max_bytes", "gauge", "Maximum heap of the server JVM.");
        Series cpu = series(all, "vectolyn_service_cpu_percent", "gauge", "CPU use of the process, percent of the machine.");
        Series rss = series(all, "vectolyn_service_rss_bytes", "gauge", "Resident memory of the process.");
        for (ServiceView view : services) {
            String labels = "{service=\"" + escape(view.service().id().value()) + "\",group=\""
                    + escape(view.service().group()) + "\"}";
            boolean isRunning = view.service().phase() == ServicePhase.RUNNING;
            running.lines.add(running.name + labels + " " + (isRunning ? 1 : 0));
            restarts.lines.add(restarts.name + labels + " " + view.service().restartAttempts());
            crashes.lines.add(crashes.name + labels + " " + view.crashes());
            ServicePhase phase = view.service().phase();
            boolean live = phase == ServicePhase.STARTING || phase == ServicePhase.RUNNING || phase == ServicePhase.STOPPING;
            if (!live || view.latest().isEmpty()) {
                continue;
            }
            ServiceSample s = view.latest().get();
            add(players, labels, s, x -> x.playersOnline() < 0 ? Double.NaN : x.playersOnline());
            add(maxPlayers, labels, s, x -> x.maxPlayers() < 0 ? Double.NaN : x.maxPlayers());
            add(tps, labels, s, ServiceSample::tps);
            add(mspt, labels, s, ServiceSample::mspt);
            add(heapUsed, labels, s, x -> x.heapUsed() < 0 ? Double.NaN : x.heapUsed());
            add(heapMax, labels, s, x -> x.heapMax() < 0 ? Double.NaN : x.heapMax());
            add(cpu, labels, s, ServiceSample::cpu);
            add(rss, labels, s, x -> x.rss() < 0 ? Double.NaN : x.rss());
        }

        Series alerts = series(all, "vectolyn_alerts_active", "gauge", "Alerts that fire now.");
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (Alert alert : monitor.activeAlerts()) {
            counts.merge(alert.severity(), 1, Integer::sum);
        }
        counts.forEach((severity, n) -> alerts.lines.add(alerts.name + "{severity=\"" + severity.id() + "\"} " + n));

        StringBuilder out = new StringBuilder();
        for (Series s : all) {
            if (s.lines.isEmpty()) {
                continue;
            }
            out.append("# HELP ").append(s.name).append(' ').append(s.help).append('\n');
            out.append("# TYPE ").append(s.name).append(' ').append(s.type).append('\n');
            s.lines.forEach(line -> out.append(line).append('\n'));
        }
        return out.toString();
    }

    private static void nodeSeries(NodeSample node, List<Series> all) {
        single(all, "vectolyn_node_cpu_percent", "CPU use of the machine in percent.", node.cpu());
        single(all, "vectolyn_node_memory_used_bytes", "Used memory of the machine or container.",
                node.memoryUsed() < 0 ? Double.NaN : node.memoryUsed());
        single(all, "vectolyn_node_memory_total_bytes", "Total memory of the machine or container.",
                node.memoryTotal() < 0 ? Double.NaN : node.memoryTotal());
        single(all, "vectolyn_node_disk_free_bytes", "Usable space on the file system of the cloud home.",
                node.diskFree() < 0 ? Double.NaN : node.diskFree());
        single(all, "vectolyn_node_disk_total_bytes", "Size of the file system of the cloud home.",
                node.diskTotal() < 0 ? Double.NaN : node.diskTotal());
    }

    private static void single(List<Series> all, String name, String help, double value) {
        Series s = series(all, name, "gauge", help);
        if (Double.isFinite(value)) {
            s.lines.add(name + " " + format(value));
        }
    }

    private static Series series(List<Series> all, String name, String type, String help) {
        Series s = new Series(name, type, help, new ArrayList<>());
        all.add(s);
        return s;
    }

    private static void add(Series series, String labels, ServiceSample sample, ToDoubleFunction<ServiceSample> value) {
        double v = value.applyAsDouble(sample);
        if (Double.isFinite(v)) {
            series.lines.add(series.name + labels + " " + format(v));
        }
    }

    /** Number in the exposition format: integers without a point, others with up to 3 decimals. */
    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Escapes a label value: backslash, double quote and line feed. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
