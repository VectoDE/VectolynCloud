package net.vectolyn.cloud.monitor.metric;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Values of one service at one moment. A value that was not measured is {@link #UNKNOWN} (counts
 * and bytes) or NaN (rates): no status report yet or an outdated one, a proxy without tick loop,
 * an operating system that does not report the memory of another process.
 *
 * @param at            when the sample was taken (whole seconds)
 * @param playersOnline players online, or {@link #UNKNOWN}
 * @param maxPlayers    player limit, or {@link #UNKNOWN}
 * @param tps           ticks per second, or NaN
 * @param mspt          milliseconds per tick, or NaN
 * @param heapUsed      used heap in bytes, or {@link #UNKNOWN}
 * @param heapMax       maximum heap in bytes, or {@link #UNKNOWN} (also when the JVM has no limit)
 * @param cpu           CPU use of the process in percent of the machine, or NaN
 * @param rss           resident memory of the process in bytes, or {@link #UNKNOWN}
 */
public record ServiceSample(Instant at, int playersOnline, int maxPlayers, double tps, double mspt,
                            long heapUsed, long heapMax, double cpu, long rss) {

    /** Marker for a count or byte value that was not measured. */
    public static final int UNKNOWN = -1;

    /** Checks the values. */
    public ServiceSample {
        Objects.requireNonNull(at, "at");
        at = Instant.ofEpochSecond(at.getEpochSecond());
        playersOnline = Math.max(UNKNOWN, playersOnline);
        maxPlayers = Math.max(UNKNOWN, maxPlayers);
        heapUsed = Math.max(UNKNOWN, heapUsed);
        heapMax = heapMax <= 0 ? UNKNOWN : heapMax;
        rss = Math.max(UNKNOWN, rss);
        tps = finiteOrNaN(tps);
        mspt = finiteOrNaN(mspt);
        cpu = finiteOrNaN(cpu);
    }

    /** A sample without any value. */
    public static ServiceSample empty(Instant at) {
        return new ServiceSample(at, UNKNOWN, UNKNOWN, Double.NaN, Double.NaN, UNKNOWN, UNKNOWN, Double.NaN, UNKNOWN);
    }

    /**
     * Returns the value of a metric that a sample holds.
     *
     * @param metric a service metric other than {@link Metric#RESTARTS} and {@link Metric#CRASHES},
     *               which come from the service core and not from samples
     * @return the value, or empty if it was not measured or the metric is not in a sample
     */
    public OptionalDouble value(Metric metric) {
        double value = switch (metric) {
            case TPS -> tps;
            case MSPT -> mspt;
            case HEAP -> heapUsed >= 0 && heapMax > 0 ? Math.min(100.0, 100.0 * heapUsed / heapMax) : Double.NaN;
            case HEAP_USED -> heapUsed >= 0 ? heapUsed : Double.NaN;
            case PLAYERS -> playersOnline >= 0 ? playersOnline : Double.NaN;
            case CPU -> cpu;
            case RSS -> rss >= 0 ? rss : Double.NaN;
            default -> Double.NaN;
        };
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    private static double finiteOrNaN(double value) {
        return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
    }
}
