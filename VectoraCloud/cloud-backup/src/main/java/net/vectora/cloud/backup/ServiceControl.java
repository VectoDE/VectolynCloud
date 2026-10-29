package net.vectora.cloud.backup;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * The part of the service manager a backup needs. cloud-node implements it on top of
 * {@code ServiceManager}: {@link #state} from {@code find(id)}, {@link #execute} with
 * {@code sendCommand} and a {@code ServiceListener} on the output lines, {@link #holdStopped} with
 * a start lock.
 */
public interface ServiceControl {

    /** What the backup needs to know about a service. */
    enum State {
        /** No process: prepared, stopped or crashed. Files may be copied directly. */
        STOPPED,
        /** Running and accepting console commands. */
        RUNNING,
        /** Starting or stopping; neither safe to copy nor able to answer commands. */
        BUSY
    }

    /** Keeps a stopped service from starting until {@link #close()}. */
    interface Hold extends AutoCloseable {
        /** Allows starts again. */
        @Override
        void close();
    }

    /**
     * The state of a service.
     *
     * @param serviceId the id
     * @return the state; {@link State#STOPPED} for a service without a process
     */
    State state(String serviceId);

    /**
     * Sends one console line to a running service and waits for an output line that confirms it.
     * The implementation must start listening before it sends, so a fast answer is not missed.
     *
     * @param serviceId the id
     * @param command the line, for example {@code save-all flush}
     * @param confirmation matches the output line that confirms the command
     * @param timeout how long to wait for it
     * @return whether a confirming line arrived in time
     * @throws IOException if the command could not be sent (service not running)
     * @throws InterruptedException if interrupted while waiting
     */
    boolean execute(String serviceId, String command, Predicate<String> confirmation, Duration timeout)
            throws IOException, InterruptedException;

    /**
     * Blocks starts of a stopped service, for a restore. Must fail if the service is not
     * {@link State#STOPPED}, checked under the same lock that guards starts.
     *
     * @param serviceId the id
     * @return the hold; close it when done
     * @throws BackupException with {@link BackupException.Reason#SERVICE_RUNNING} if it runs or is busy
     */
    Hold holdStopped(String serviceId) throws BackupException;
}
