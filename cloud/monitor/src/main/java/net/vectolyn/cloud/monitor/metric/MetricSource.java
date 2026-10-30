package net.vectolyn.cloud.monitor.metric;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Measures what the operating system knows: CPU and memory of the server processes and of the
 * machine, free space in the home. {@link SystemMetricSource} is the implementation for the running
 * node; tests pass a fake.
 *
 * <p>Each method is called once per sample interval: {@link #processes} from the monitor's probe
 * thread, {@link #node} from its sampling thread, so the two may run at the same time, but neither
 * runs twice at once. {@link #processes} may be slow (a helper program); {@link #node} must not
 * be. An implementation must not throw for a value it cannot measure; it returns the unknown
 * marker instead.
 */
public interface MetricSource {

    /**
     * Measures the given server processes.
     *
     * @param pids process ids of the running services
     * @return values per pid; a pid without entry or with unknown values is reported as not measured
     */
    Map<Long, ProcessMetrics> processes(Set<Long> pids);

    /**
     * Measures the node.
     *
     * @param at the time of the sample
     * @return the values; unknown ones are marked as such
     */
    NodeSample node(Instant at);

    /**
     * CPU and resident memory of one process.
     *
     * @param cpu CPU use in percent of the whole machine, or NaN
     * @param rss resident memory in bytes (working set on Windows), or {@link ServiceSample#UNKNOWN}
     */
    record ProcessMetrics(double cpu, long rss) {

        /** Nothing measured. */
        public static final ProcessMetrics UNKNOWN = new ProcessMetrics(Double.NaN, ServiceSample.UNKNOWN);
    }
}
