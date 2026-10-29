package net.vectora.cloud.node.scaling;

import java.util.List;
import java.util.Objects;
import net.vectora.cloud.node.service.ServiceException;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceManager;
import net.vectora.protocol.ServiceId;

/** What the autoscaler starts and stops; the service core in the node, a fake in tests. */
public interface ScalingTarget {

    /**
     * Every service the node knows.
     *
     * @return the services
     */
    List<ServiceInfo> services();

    /**
     * Starts one more dynamic service of a task at the lowest free index
     * ({@link ServiceManager#startDynamic(String)}).
     *
     * @param task task name
     * @return the service
     * @throws ServiceException if it cannot start
     */
    ServiceInfo startDynamic(String task) throws ServiceException;

    /**
     * Starts the static service of a task with this index ({@link ServiceManager#start(String, int)}).
     *
     * @param task  task name
     * @param index index within the group
     * @return the service
     * @throws ServiceException if it cannot start
     */
    ServiceInfo start(String task, int index) throws ServiceException;

    /**
     * Stops a service with its stop command and waits for it ({@link ServiceManager#stop(ServiceId)}).
     *
     * @param service the service
     * @return the service afterwards
     * @throws ServiceException if it is unknown
     */
    ServiceInfo stop(ServiceId service) throws ServiceException;

    /**
     * The service core of a node as target.
     *
     * @param services the service core
     * @return the target
     */
    static ScalingTarget of(ServiceManager services) {
        Objects.requireNonNull(services, "services");
        return new ScalingTarget() {
            @Override
            public List<ServiceInfo> services() {
                return services.services();
            }

            @Override
            public ServiceInfo startDynamic(String task) throws ServiceException {
                return services.startDynamic(task);
            }

            @Override
            public ServiceInfo start(String task, int index) throws ServiceException {
                return services.start(task, index);
            }

            @Override
            public ServiceInfo stop(ServiceId service) throws ServiceException {
                return services.stop(service);
            }
        };
    }
}
