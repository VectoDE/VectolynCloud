package net.vectolyn.cloud.monitor.metric;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link MetricSource} of the running node, built on the JDK and, for the resident memory of other
 * processes, on {@link ResidentMemoryProbe}.
 *
 * <ul>
 *   <li><b>Process CPU:</b> {@link ProcessHandle.Info#totalCpuDuration()} between two samples,
 *       divided by the elapsed time and the number of processors. The first sample of a process has
 *       no previous value and is unknown. A pid whose start time changed (reused by another program)
 *       starts over.</li>
 *   <li><b>Process memory:</b> see {@link ResidentMemoryProbe}.</li>
 *   <li><b>Node CPU and memory:</b> {@code com.sun.management.OperatingSystemMXBean}, which every
 *       HotSpot JDK has and which respects container limits. On Linux outside a container the
 *       available memory comes from {@code /proc/meminfo} ({@code MemAvailable}), because the
 *       MXBean counts the page cache as used.</li>
 *   <li><b>Disk:</b> {@link FileStore#getUsableSpace()} of the home's file system.</li>
 * </ul>
 * Anything a platform does not report stays unknown; nothing is refused.
 */
public final class SystemMetricSource implements MetricSource {

    private static final Logger LOG = Logger.getLogger(SystemMetricSource.class.getName());

    private final Path home;
    private final ResidentMemoryProbe memory;
    private final int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
    private final Map<Long, CpuMark> previous = new HashMap<>();
    private boolean memoryFailureLogged;

    /**
     * Creates the source.
     *
     * @param home   cloud home, for the free space
     * @param memory probe for the resident memory of the services
     */
    public SystemMetricSource(Path home, ResidentMemoryProbe memory) {
        this.home = Objects.requireNonNull(home, "home");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    /**
     * Creates the source with the probe for the running operating system.
     *
     * @param home cloud home
     * @return the source
     */
    public static SystemMetricSource forHome(Path home) {
        return new SystemMetricSource(home, ResidentMemoryProbe.forCurrentSystem());
    }

    /** Which memory probe is used, for the start log. */
    public String memoryProbe() {
        return memory.description();
    }

    @Override
    public synchronized Map<Long, ProcessMetrics> processes(Set<Long> pids) {
        Map<Long, Long> rss = Map.of();
        try {
            rss = memory.measure(pids);
            memoryFailureLogged = false;
        } catch (IOException e) {
            if (!memoryFailureLogged) {
                LOG.log(Level.WARNING, "Process memory could not be read with {0}: {1}. RSS shows - until it works again.",
                        new Object[] {memory.description(), e.getMessage()});
                memoryFailureLogged = true;
            }
        }
        long nowNanos = System.nanoTime();
        Map<Long, ProcessMetrics> result = new HashMap<>();
        for (long pid : pids) {
            double cpu = cpu(pid, nowNanos);
            Long bytes = rss.get(pid);
            result.put(pid, new ProcessMetrics(cpu, bytes == null ? ServiceSample.UNKNOWN : bytes));
        }
        previous.keySet().retainAll(pids);
        return result;
    }

    private double cpu(long pid, long nowNanos) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            previous.remove(pid);
            return Double.NaN;
        }
        ProcessHandle.Info info = handle.get().info();
        Optional<Duration> total = info.totalCpuDuration();
        if (total.isEmpty()) {
            return Double.NaN;
        }
        Instant started = info.startInstant().orElse(null);
        CpuMark mark = new CpuMark(total.get().toNanos(), nowNanos, started);
        CpuMark before = previous.put(pid, mark);
        if (before == null || !Objects.equals(before.started, started)) {
            return Double.NaN;
        }
        return cpuPercent(before.cpuNanos, mark.cpuNanos, before.wallNanos, mark.wallNanos, processors);
    }

    /**
     * CPU use between two readings in percent of the whole machine.
     *
     * @return 0 to 100, or NaN if no time passed or the counter went backwards
     */
    static double cpuPercent(long cpuBefore, long cpuNow, long wallBefore, long wallNow, int processors) {
        long wall = wallNow - wallBefore;
        long cpu = cpuNow - cpuBefore;
        if (wall <= 0 || cpu < 0 || processors < 1) {
            return Double.NaN;
        }
        return Math.min(100.0, 100.0 * cpu / ((double) wall * processors));
    }

    @Override
    public NodeSample node(Instant at) {
        double cpu = Double.NaN;
        long total = NodeSample.UNKNOWN;
        long free = NodeSample.UNKNOWN;
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getCpuLoad();
            cpu = load >= 0 ? load * 100.0 : Double.NaN;
            total = sun.getTotalMemorySize();
            free = sun.getFreeMemorySize();
            OptionalLong available = linuxAvailableMemory(total);
            if (available.isPresent()) {
                free = available.getAsLong();
            }
        }
        long used = total > 0 && free >= 0 ? Math.max(0, total - free) : NodeSample.UNKNOWN;
        long diskFree = NodeSample.UNKNOWN;
        long diskTotal = NodeSample.UNKNOWN;
        try {
            FileStore store = Files.getFileStore(home);
            diskFree = store.getUsableSpace();
            diskTotal = store.getTotalSpace();
        } catch (IOException | SecurityException e) {
            LOG.log(Level.FINE, "Free space of the home could not be read", e);
        }
        return new NodeSample(at, cpu, used, total, diskFree, diskTotal);
    }

    /** {@code MemAvailable} from {@code /proc/meminfo} if its total matches the MXBean's (no container limit). */
    private static OptionalLong linuxAvailableMemory(long mxTotal) {
        Path meminfo = Path.of("/proc/meminfo");
        if (mxTotal <= 0 || !Files.isReadable(meminfo)) {
            return OptionalLong.empty();
        }
        try {
            return parseMeminfo(Files.readString(meminfo, StandardCharsets.US_ASCII), mxTotal);
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Reads {@code MemAvailable} from {@code /proc/meminfo} text.
     *
     * @param text    file content
     * @param mxTotal total memory the MXBean reports
     * @return available bytes, or empty if a value is missing or {@code MemTotal} differs from
     *     {@code mxTotal} by more than 1 % (then a container limit applies and the MXBean is right)
     */
    static OptionalLong parseMeminfo(String text, long mxTotal) {
        long total = -1;
        long available = -1;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3 || !parts[2].equals("kB") || !parts[1].matches("[0-9]{1,15}")) {
                continue;
            }
            long kilobytes;
            try {
                kilobytes = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (parts[0].equals("MemTotal:")) {
                total = kilobytes * 1024;
            } else if (parts[0].equals("MemAvailable:")) {
                available = kilobytes * 1024;
            }
        }
        if (total <= 0 || available < 0 || Math.abs(total - mxTotal) > mxTotal / 100) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.min(available, total));
    }

    private record CpuMark(long cpuNanos, long wallNanos, Instant started) {
    }
}
