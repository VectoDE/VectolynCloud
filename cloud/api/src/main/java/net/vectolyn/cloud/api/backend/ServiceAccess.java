package net.vectolyn.cloud.api.backend;

import java.util.List;
import java.util.Optional;

/**
 * What the API may do with the services of the node. cloud-node implements it on top of its
 * {@code ServiceManager}; the console's {@code service} command does the same things.
 *
 * <p>Methods are called on request threads of the API (virtual threads), never on a Netty event
 * loop, so they may block: {@link #stop} returns once the process has ended. Every message of an
 * {@link ApiBackendException} reaches the client and must not contain secrets.
 */
public interface ServiceAccess {

    /**
     * Every service of the node.
     *
     * @return the services, in a stable order
     */
    List<ServiceView> list();

    /**
     * One service.
     *
     * @param id the service id; already checked against {@code [a-z0-9][a-z0-9_-]{0,63}}
     * @return the service, or empty if there is none with this id
     */
    Optional<ServiceView> find(String id);

    /**
     * Starts one more service of a task, like {@code service start <task>}.
     *
     * @param task the task name; already checked against the name pattern
     * @return the new service
     * @throws ApiBackendException if the task does not exist, the setup is not complete or no port is free
     */
    ServiceView start(String task) throws ApiBackendException;

    /**
     * Stops a service and waits until its process has ended.
     *
     * @param id the service id
     * @return the service after the stop
     * @throws ApiBackendException if there is no such service
     * @throws InterruptedException if the request thread is interrupted while waiting
     */
    ServiceView stop(String id) throws ApiBackendException, InterruptedException;

    /**
     * Stops a service and starts it again.
     *
     * @param id the service id
     * @return the service after the new start
     * @throws ApiBackendException if there is no such service or it cannot start
     * @throws InterruptedException if the request thread is interrupted while waiting
     */
    ServiceView restart(String id) throws ApiBackendException, InterruptedException;

    /**
     * Types one line into the server's console.
     *
     * @param id   the service id
     * @param line the command, 1 to 1024 characters without control characters (checked by the API)
     * @throws ApiBackendException if there is no such service or it does not run
     */
    void command(String id, String line) throws ApiBackendException;
}
