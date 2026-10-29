package net.vectora.cloud.node.service;

import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.protocol.ServiceId;

/**
 * Receives what happens to the services of the node, for example the console (stage 2 PR 10) or
 * the API (PR 15). Both methods are called on internal threads of the node (state changes on one
 * shared thread, output lines on the output thread of each service) and must return
 * quickly; an exception is logged and does not reach other listeners. A listener that needs only
 * one of the two leaves the other one empty.
 */
public interface ServiceListener {

    /**
     * A service changed its state. Calls for one service come in the order the changes happened.
     *
     * @param service  the service after the change
     * @param previous the state before, or {@code null} when the service was just created
     */
    void stateChanged(ServiceInfo service, ServiceState previous);

    /**
     * A service changed its state; with the configuration as it was at the moment of the change,
     * which may differ from the one at delivery. The service core calls this one; by default it
     * passes on to {@link #stateChanged(ServiceInfo, ServiceState)}.
     *
     * @param service  the service after the change
     * @param previous the state before, or {@code null} when the service was just created
     * @param config   the configuration at the change
     */
    default void stateChanged(ServiceInfo service, ServiceState previous, ConfigSnapshot config) {
        stateChanged(service, previous);
    }

    /**
     * A service printed a line. The line has passed the log filter: secrets and the service token
     * are already replaced by {@code ***}.
     *
     * @param service the service
     * @param line    one line without its line break
     */
    void output(ServiceId service, String line);
}
