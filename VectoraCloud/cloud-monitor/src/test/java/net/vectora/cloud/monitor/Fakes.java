package net.vectora.cloud.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.vectora.cloud.monitor.metric.MetricSource;
import net.vectora.cloud.monitor.metric.NodeSample;
import net.vectora.protocol.ServiceId;

/** Test doubles shared by the monitor tests. */
public final class Fakes {

    private Fakes() {
    }

    /** A clock the test moves by hand. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void advanceSeconds(long seconds) {
            advance(Duration.ofSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A service directory the test edits. */
    public static final class Directory implements ServiceDirectory {
        private final Map<String, MonitoredService> services = new java.util.LinkedHashMap<>();

        public Directory put(String id, String group, boolean proxy, ServicePhase phase, long pid, int restarts) {
            services.put(id, new MonitoredService(new ServiceId(id), group, proxy, phase,
                    pid > 0 ? OptionalLong.of(pid) : OptionalLong.empty(), restarts));
            return this;
        }

        public Directory running(String id, String group, long pid) {
            return put(id, group, false, ServicePhase.RUNNING, pid, 0);
        }

        public void remove(String id) {
            services.remove(id);
        }

        @Override
        public synchronized List<MonitoredService> services() {
            return new ArrayList<>(services.values());
        }
    }

    /** A metric source with fixed values. */
    public static final class Source implements MetricSource {
        public final Map<Long, ProcessMetrics> processes = new HashMap<>();
        public double nodeCpu = 10;
        public long memoryUsed = 4L << 30;
        public long memoryTotal = 16L << 30;
        public long diskFree = 100L << 30;
        public long diskTotal = 500L << 30;
        public final List<Set<Long>> asked = new ArrayList<>();

        @Override
        public Map<Long, ProcessMetrics> processes(Set<Long> pids) {
            asked.add(Set.copyOf(pids));
            Map<Long, ProcessMetrics> result = new HashMap<>();
            for (long pid : pids) {
                ProcessMetrics m = processes.get(pid);
                if (m != null) {
                    result.put(pid, m);
                }
            }
            return result;
        }

        @Override
        public NodeSample node(Instant at) {
            return new NodeSample(at, nodeCpu, memoryUsed, memoryTotal, diskFree, diskTotal);
        }
    }

    /** Collects log records of a logger (and its children) while open. */
    public static final class LogCapture extends Handler implements AutoCloseable {
        private final Logger logger;
        public final List<LogRecord> records = new CopyOnWriteArrayList<>();

        public LogCapture(String name) {
            logger = Logger.getLogger(name);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        public String text() {
            StringBuilder out = new StringBuilder();
            for (LogRecord r : records) {
                String message = r.getMessage();
                if (r.getParameters() != null) {
                    message = java.text.MessageFormat.format(message, r.getParameters());
                }
                out.append(r.getLevel()).append(' ').append(message);
                if (r.getThrown() != null) {
                    out.append(' ').append(r.getThrown());
                }
                out.append('\n');
            }
            return out.toString();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
