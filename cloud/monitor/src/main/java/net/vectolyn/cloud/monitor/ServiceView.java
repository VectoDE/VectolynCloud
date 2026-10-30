package net.vectolyn.cloud.monitor;

import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.monitor.metric.ServiceSample;

/**
 * One service as the console, the API and the Prometheus output see it.
 *
 * @param service the directory entry
 * @param latest  the newest sample, if the service had a process since the monitor started
 * @param crashes crashes seen since the monitor started
 */
public record ServiceView(MonitoredService service, Optional<ServiceSample> latest, int crashes) {

    /** Checks the values. */
    public ServiceView {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(latest, "latest");
    }
}
