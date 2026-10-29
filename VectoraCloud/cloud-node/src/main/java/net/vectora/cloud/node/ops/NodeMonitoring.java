package net.vectora.cloud.node.ops;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.monitor.Monitor;
import net.vectora.cloud.monitor.MonitoredService;
import net.vectora.cloud.monitor.ServiceDirectory;
import net.vectora.cloud.monitor.ServicePhase;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceListener;
import net.vectora.cloud.node.service.ServiceManager;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.ServiceId;

/**
 * The node seen by cloud-monitor ({@code docs/cloud/monitor.md#hooking-it-into-the-node}): the
 * service list for sampling, and every state change. {@link ServicePhase} has the names of
 * {@link ServiceState}.
 */
public final class NodeMonitoring implements ServiceDirectory, ServiceListener {

    private final ServiceManager services;
    private final Supplier<ConfigSnapshot> config;
    private volatile Monitor monitor;

    /**
     * Creates the adapter.
     *
     * @param services the service core
     * @param config   the current configuration
     */
    public NodeMonitoring(ServiceManager services, Supplier<ConfigSnapshot> config) {
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Sets the monitor that hears the state changes, once it is built.
     *
     * @param value the monitor
     */
    public void attach(Monitor value) {
        this.monitor = Objects.requireNonNull(value, "value");
    }

    @Override
    public List<MonitoredService> services() {
        ConfigSnapshot snapshot = config.get();
        return services.services().stream().map(info -> {
            GroupConfig group = snapshot.groups().get(info.group());
            boolean proxy = group != null && group.software() == ServerSoftware.VELOCITY;
            return new MonitoredService(info.id(), info.group(), proxy, phase(info.state()), info.pid(),
                    info.restartAttempts());
        }).toList();
    }

    @Override
    public void stateChanged(ServiceInfo service, ServiceState previous) {
        Monitor target = monitor;
        if (target != null) {
            target.serviceStateChanged(service.id(), phase(service.state()), previous == null ? null : phase(previous));
        }
    }

    @Override
    public void output(ServiceId service, String line) {
        // Output is not monitored.
    }

    static ServicePhase phase(ServiceState state) {
        return ServicePhase.valueOf(state.name());
    }
}
