package net.vectolyn.cloud.config.operation;

import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.error.ErrorCode;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.validation.ConfigNames;

/** Shared lookups of the operations, with errors for missing entries. */
final class Lookups {

    private Lookups() {
    }

    static GroupConfig group(ConfigSnapshot snapshot, String name) throws ConfigException {
        GroupConfig group = snapshot.groups().get(name);
        if (group == null) {
            throw new ConfigException(ConfigError.of(CloudHome.GROUPS_DIR + "/", "", ErrorCode.UNKNOWN_GROUP, ConfigNames.safeArgument(name)));
        }
        return group;
    }

    static TaskConfig task(ConfigSnapshot snapshot, String name) throws ConfigException {
        TaskConfig task = snapshot.tasks().get(name);
        if (task == null) {
            throw new ConfigException(ConfigError.of(CloudHome.TASKS_DIR + "/", "", ErrorCode.UNKNOWN_TASK, ConfigNames.safeArgument(name)));
        }
        return task;
    }
}
