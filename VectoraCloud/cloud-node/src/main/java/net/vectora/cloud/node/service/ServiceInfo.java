package net.vectora.cloud.node.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.protocol.ServiceId;

/**
 * Read-only view of one service at one moment, for the console, the API and the listeners.
 *
 * @param id              service id
 * @param group           group name
 * @param task            task name
 * @param index           index within the group
 * @param mode            static or dynamic
 * @param state           state at that moment
 * @param port            port of the server
 * @param workDirectory   folder of the process
 * @param pid             process id while a process exists
 * @param startedAt       when the current or last process started
 * @param exitCode        exit code of the last process, once it ended
 * @param restartAttempts restarts after crashes in a row (reset once the service ran stable)
 */
public record ServiceInfo(ServiceId id, String group, String task, int index, TaskMode mode, ServiceState state,
                          int port, Path workDirectory, OptionalLong pid, Optional<Instant> startedAt,
                          OptionalInt exitCode, int restartAttempts) {

    /** Checks the values. */
    public ServiceInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pid, "pid");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(exitCode, "exitCode");
    }
}
