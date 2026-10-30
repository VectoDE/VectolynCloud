package net.vectolyn.cloud.monitor.metric;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every value the monitor records and the alert rules can test. The {@link #id()} is the name used
 * in rules ({@code tps < 18 for 60s}), in the console and in the Prometheus output.
 *
 * <p>Percent values are 0 to 100, byte values are bytes, counts are whole numbers.
 */
public enum Metric {
    /** Ticks per second of a game server, from the bridge. Not measured on proxies. */
    TPS("tps", Scope.SERVICE, MetricUnit.NUMBER, 0, 1_000),
    /** Milliseconds per tick of a game server, from the bridge. Not measured on proxies. */
    MSPT("mspt", Scope.SERVICE, MetricUnit.MILLISECONDS, 0, 60_000),
    /** Used heap in percent of the maximum heap, from the bridge. Missing if the maximum is unknown. */
    HEAP("heap", Scope.SERVICE, MetricUnit.PERCENT, 0, 100),
    /** Used heap in bytes, from the bridge. */
    HEAP_USED("heap-used", Scope.SERVICE, MetricUnit.BYTES, 0, MetricUnit.MAX_BYTES),
    /** Players online, from the bridge. */
    PLAYERS("players", Scope.SERVICE, MetricUnit.NUMBER, 0, 1_000_000),
    /** CPU use of the server process in percent of the whole machine (all cores = 100). */
    CPU("cpu", Scope.SERVICE, MetricUnit.PERCENT, 0, 100),
    /** Resident memory of the server process (working set on Windows). */
    RSS("rss", Scope.SERVICE, MetricUnit.BYTES, 0, MetricUnit.MAX_BYTES),
    /** Restarts after crashes in a row, as the service core counts them. */
    RESTARTS("restarts", Scope.SERVICE, MetricUnit.NUMBER, 0, 1_000),
    /** Crashes of a service within the window of the rule ({@code crashes >= 3 within 10m}). */
    CRASHES("crashes", Scope.SERVICE, MetricUnit.NUMBER, 0, 1_000),
    /** CPU use of the whole machine in percent. */
    NODE_CPU("node-cpu", Scope.NODE, MetricUnit.PERCENT, 0, 100),
    /** Used memory of the machine (or container) in percent. */
    NODE_MEMORY("node-memory", Scope.NODE, MetricUnit.PERCENT, 0, 100),
    /** Usable space on the file system of the cloud home. */
    DISK_FREE("disk-free", Scope.NODE, MetricUnit.BYTES, 0, MetricUnit.MAX_BYTES);

    /** Whether a metric belongs to one service or to the node. */
    public enum Scope {
        /** Measured per service. */
        SERVICE,
        /** Measured once for the node. */
        NODE
    }

    private final String id;
    private final Scope scope;
    private final MetricUnit unit;
    private final double min;
    private final double max;

    Metric(String id, Scope scope, MetricUnit unit, double min, double max) {
        this.id = id;
        this.scope = scope;
        this.unit = unit;
        this.min = min;
        this.max = max;
    }

    /** Name in rules, commands and exports. */
    public String id() {
        return id;
    }

    /** Service or node. */
    public Scope scope() {
        return scope;
    }

    /** Unit of the value. */
    public MetricUnit unit() {
        return unit;
    }

    /** Lowest value a rule may compare with. */
    public double min() {
        return min;
    }

    /** Highest value a rule may compare with. */
    public double max() {
        return max;
    }

    /** Whether rules on this metric count events in a window ({@code within}) instead of holding a level ({@code for}). */
    public boolean isWindowCount() {
        return this == CRASHES;
    }

    /** Whether the value comes from the bridge's tick loop, which a proxy does not have. */
    public boolean needsTickLoop() {
        return this == TPS || this == MSPT;
    }

    /**
     * Finds a metric by its id.
     *
     * @param id for example {@code tps}
     * @return the metric, or empty for an unknown id
     */
    public static Optional<Metric> byId(String id) {
        return Arrays.stream(values()).filter(m -> m.id.equals(id)).findFirst();
    }
}
