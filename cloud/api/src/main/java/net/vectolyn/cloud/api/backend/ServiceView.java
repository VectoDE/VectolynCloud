package net.vectolyn.cloud.api.backend;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * One service as the API shows it. cloud-node builds it from its {@code ServiceInfo} and the last
 * status report of the cloud channel. It holds no path and no token, so it can be sent as it is.
 *
 * @param id              service id, for example {@code lobby-1}
 * @param group           group name
 * @param task            task name
 * @param index           index within the group
 * @param mode            {@code static} or {@code dynamic}
 * @param state           {@code prepared}, {@code starting}, {@code running}, {@code stopping},
 *                        {@code stopped} or {@code crashed}
 * @param port            port of the server
 * @param pid             process id while a process exists, otherwise null
 * @param startedAt       when the current or last process started (ISO-8601), or null
 * @param exitCode        exit code of the last process once it ended, or null
 * @param restartAttempts restarts after crashes in a row
 * @param playersOnline   players from the last status report, or null
 * @param maxPlayers      player limit from the last status report, or null
 */
@JsonPropertyOrder({"id", "group", "task", "index", "mode", "state", "port", "pid", "started-at", "exit-code",
        "restart-attempts", "players-online", "max-players"})
public record ServiceView(String id, String group, String task, int index, String mode, String state, int port,
                          Long pid, String startedAt, Integer exitCode, int restartAttempts, Integer playersOnline,
                          Integer maxPlayers) {

    /** Checks the required values. */
    public ServiceView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
    }
}
