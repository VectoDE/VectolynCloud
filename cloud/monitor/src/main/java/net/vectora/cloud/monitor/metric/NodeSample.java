package net.vectora.cloud.monitor.metric;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Values of the node at one moment. Unknown values are NaN (CPU) or {@link #UNKNOWN}.
 *
 * @param at          when the sample was taken (whole seconds)
 * @param cpu         CPU use of the machine in percent, or NaN
 * @param memoryUsed  used memory of the machine or container in bytes, or {@link #UNKNOWN}
 * @param memoryTotal total memory in bytes, or {@link #UNKNOWN}
 * @param diskFree    usable bytes on the file system of the home, or {@link #UNKNOWN}
 * @param diskTotal   size of that file system in bytes, or {@link #UNKNOWN}
 */
public record NodeSample(Instant at, double cpu, long memoryUsed, long memoryTotal, long diskFree, long diskTotal) {

    /** Marker for a byte value that was not measured. */
    public static final long UNKNOWN = -1;

    /** Checks the values. */
    public NodeSample {
        Objects.requireNonNull(at, "at");
        at = Instant.ofEpochSecond(at.getEpochSecond());
        cpu = Double.isFinite(cpu) && cpu >= 0 ? Math.min(100.0, cpu) : Double.NaN;
        memoryUsed = Math.max(UNKNOWN, memoryUsed);
        memoryTotal = memoryTotal <= 0 ? UNKNOWN : memoryTotal;
        diskFree = Math.max(UNKNOWN, diskFree);
        diskTotal = diskTotal <= 0 ? UNKNOWN : diskTotal;
    }

    /** A sample without any value. */
    public static NodeSample empty(Instant at) {
        return new NodeSample(at, Double.NaN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    /**
     * Returns the value of a node metric.
     *
     * @param metric {@link Metric#NODE_CPU}, {@link Metric#NODE_MEMORY} or {@link Metric#DISK_FREE}
     * @return the value, or empty if it was not measured
     */
    public OptionalDouble value(Metric metric) {
        double value = switch (metric) {
            case NODE_CPU -> cpu;
            case NODE_MEMORY -> memoryUsed >= 0 && memoryTotal > 0
                    ? Math.min(100.0, 100.0 * memoryUsed / memoryTotal) : Double.NaN;
            case DISK_FREE -> diskFree >= 0 ? diskFree : Double.NaN;
            default -> Double.NaN;
        };
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }
}
