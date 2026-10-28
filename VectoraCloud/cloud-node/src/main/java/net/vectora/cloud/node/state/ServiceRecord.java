package net.vectora.cloud.node.state;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.node.service.ServiceState;

/**
 * One service in {@code state/services.json}: what the node needs after a restart of the cloud to
 * find the process it left behind and the folder it used. Never contains a token or a secret.
 *
 * @param id             service id, {@code <group>-<index>}
 * @param group          group name
 * @param task           task name
 * @param index          index within the group
 * @param mode           static or dynamic
 * @param state          last known state
 * @param pid            process id, 0 if no process was started
 * @param startedAt      start time of the process as reported by the operating system, in
 *                       milliseconds since the epoch; 0 if unknown. Used to tell the process apart
 *                       from a later one that got the same pid.
 * @param port           port of the service
 * @param workDirectory  folder of the service relative to the cloud home, with {@code /}
 */
@JsonPropertyOrder({"id", "group", "task", "index", "mode", "state", "pid", "started-at", "port", "work-directory"})
public record ServiceRecord(String id, String group, String task, int index, TaskMode mode, ServiceState state,
                            long pid, long startedAt, int port, String workDirectory) {
}
