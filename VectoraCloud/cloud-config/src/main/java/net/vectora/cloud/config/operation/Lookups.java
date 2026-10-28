package net.vectora.cloud.config.operation;

import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.TaskConfig;

/** Shared lookups of the operations, with errors for missing entries. */
final class Lookups {

    private Lookups() {
    }

    static GroupConfig group(ConfigSnapshot snapshot, String name) throws ConfigException {
        GroupConfig group = snapshot.groups().get(name);
        if (group == null) {
            throw new ConfigException(ConfigError.of(CloudHome.GROUPS_DIR + "/", "", ErrorCode.UNKNOWN_GROUP, name));
        }
        return group;
    }

    static TaskConfig task(ConfigSnapshot snapshot, String name) throws ConfigException {
        TaskConfig task = snapshot.tasks().get(name);
        if (task == null) {
            throw new ConfigException(ConfigError.of(CloudHome.TASKS_DIR + "/", "", ErrorCode.UNKNOWN_TASK, name));
        }
        return task;
    }
}
