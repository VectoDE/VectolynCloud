package net.vectolyn.cloud.api;

import java.util.List;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ScalingConfig;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.model.TimeSpan;

/** A valid lobby group and task for the tests. */
final class ApiSamples {

    private ApiSamples() {
    }

    static GroupConfig lobbyGroup() {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, "Lobby-Server", ServerSoftware.PAPER, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("768M")), List.of(), CombatSystem.MODERN);
    }

    static TaskConfig lobbyTask() {
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, "lobby",
                new ScalingConfig(1, 1, 1, 50, 80, 50, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of());
    }
}
