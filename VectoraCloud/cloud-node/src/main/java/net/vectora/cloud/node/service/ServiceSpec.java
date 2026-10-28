package net.vectora.cloud.node.service;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.protocol.ServiceId;

/**
 * Everything the node decided about one service before it starts: name, configuration, folder
 * and port. Immutable; a restart of a static service creates a new spec with the same id and folder.
 *
 * @param id            {@code <group>-<index>}
 * @param groupName     group name
 * @param taskName      task that asked for the service
 * @param index         index within the group, 1 or more
 * @param group         the group configuration at the time the service was planned
 * @param mode          static (keeps its folder) or dynamic (works in {@code temp/}, deleted after the stop)
 * @param kind          the server kind of the group's template, empty for a custom template
 * @param templateDir   {@code <home>/<template>/template}
 * @param workDirectory folder the process runs in
 * @param port          port the server listens on
 */
public record ServiceSpec(ServiceId id, String groupName, String taskName, int index, GroupConfig group,
                          TaskMode mode, Optional<ServerKind> kind, Path templateDir, Path workDirectory, int port) {

    /** Checks the values. */
    public ServiceSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(templateDir, "templateDir");
        Objects.requireNonNull(workDirectory, "workDirectory");
        if (index < 1 || index > ServiceIds.MAX_INDEX) {
            throw new IllegalArgumentException("index out of range");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
    }

    /**
     * Tells whether the service keeps its folder between starts.
     *
     * @return true for {@link TaskMode#STATIC}
     */
    public boolean isStatic() {
        return mode == TaskMode.STATIC;
    }

    /**
     * Tells whether the service is a Velocity proxy.
     *
     * @return true if the group runs Velocity
     */
    public boolean isProxy() {
        return group.software() == ServerSoftware.VELOCITY;
    }
}
