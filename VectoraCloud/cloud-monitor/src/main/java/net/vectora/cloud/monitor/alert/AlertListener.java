package net.vectora.cloud.monitor.alert;

/**
 * Receives alert events. Called on the monitor thread, in the order the events happened; must
 * return quickly (hand slow work such as HTTP to an executor of your own). An exception is logged
 * and does not reach other listeners.
 */
@FunctionalInterface
public interface AlertListener {

    /**
     * An alert fired, ended or was acknowledged.
     *
     * @param event what happened
     */
    void onAlert(AlertEvent event);
}
