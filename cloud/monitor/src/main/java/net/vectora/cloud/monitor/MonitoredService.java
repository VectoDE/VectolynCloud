package net.vectora.cloud.monitor;

import java.util.Objects;
import java.util.OptionalLong;
import net.vectora.protocol.ServiceId;

/**
 * What the monitor needs to know about one service, from the node's service core.
 *
 * @param id              service id
 * @param group           group name, for the rules' group filter
 * @param proxy           whether it is a proxy (Velocity). Proxies have no tick loop, so TPS and
 *                        MSPT are not measured on them, whatever the bridge reports.
 * @param phase           life cycle state
 * @param pid             process id while a process exists
 * @param restartAttempts restarts after crashes in a row, as the service core counts them
 */
public record MonitoredService(ServiceId id, String group, boolean proxy, ServicePhase phase, OptionalLong pid,
                               int restartAttempts) {

    /** Checks the values. */
    public MonitoredService {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(pid, "pid");
        if (restartAttempts < 0) {
            throw new IllegalArgumentException("restart attempts must not be negative");
        }
    }
}
