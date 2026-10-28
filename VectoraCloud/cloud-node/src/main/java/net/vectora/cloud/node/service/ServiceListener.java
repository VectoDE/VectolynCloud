package net.vectora.cloud.node.service;

import net.vectora.protocol.ServiceId;

/**
 * Receives what happens to the services of the node, for example the console (stage 2 PR 10) or
 * the API (PR 15). Both methods are called on internal threads of the node and must return
 * quickly; an exception is logged and does not reach other listeners.
 */
public interface ServiceListener {

    /**
     * A service changed its state. Calls for one service come in the order the changes happened.
     *
     * @param service  the service after the change
     * @param previous the state before, or {@code null} when the service was just created
     */
    default void stateChanged(ServiceInfo service, ServiceState previous) {
    }

    /**
     * A service printed a line. The line has passed the log filter: secrets and the service token
     * are already replaced by {@code ***}.
     *
     * @param service the service
     * @param line    one line without its line break
     */
    default void output(ServiceId service, String line) {
    }
}
