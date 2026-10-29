package net.vectora.cloud.node.scaling;

import java.time.Duration;
import java.util.List;
import net.vectora.cloud.node.channel.ChannelBridge;
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
     * Moves the players of a service to other services of its group and waits until it is empty
     * ({@link ChannelBridge#drain}).
     *
     * @param service the service
     * @param timeout how long to wait
     * @return whether no player is left
     * @throws InterruptedException if the wait is interrupted
     */
    boolean drain(ServiceId service, Duration timeout) throws InterruptedException;

    /**
     * The service core and the cloud channel of a node as target.
     *
     * @param services the service core
     * @param channel  the cloud channel, which moves the players
     * @return the target
     */
    static ScalingTarget of(ServiceManager services, ChannelBridge channel) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(channel, "channel");
        return new ScalingTarget() {
            @Override
            public boolean drain(ServiceId service, Duration timeout) throws InterruptedException {
                return channel.drain(service, timeout);
            }

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
