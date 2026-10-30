package net.vectolyn.cloud.monitor;

import java.util.List;

/**
 * The services the node knows right now. The node implements it on top of
 * {@code ServiceManager.services()} and the group configuration (for {@link MonitoredService#proxy()}):
 *
 * <pre>{@code
 * ServiceDirectory directory = () -> services.services().stream()
 *         .map(s -> new MonitoredService(s.id(), s.group(),
 *                 config.snapshot().groups().get(s.group()).software() == ServerSoftware.VELOCITY,
 *                 ServicePhase.valueOf(s.state().name()), s.pid(), s.restartAttempts()))
 *         .toList();
 * }</pre>
 *
 * Called on the monitor thread once per sample and by the console commands; must be cheap and
 * thread-safe.
 */
@FunctionalInterface
public interface ServiceDirectory {

    /**
     * Lists the services.
     *
     * @return every service of this node, in any order
     */
    List<MonitoredService> services();
}
