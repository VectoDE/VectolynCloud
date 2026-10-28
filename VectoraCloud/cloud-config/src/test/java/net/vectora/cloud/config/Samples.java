package net.vectora.cloud.config;

import java.util.List;
import java.util.Map;
import net.vectora.cloud.config.model.CatalogKind;
import net.vectora.cloud.config.model.CatalogReference;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.CombatSystem;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.HeapConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.config.model.TimeSpan;

/** Valid example configuration shared by the tests. */
public final class Samples {

    private Samples() {
    }

    /** Lobby group on Paper 26.3 with two catalog entries. */
    public static GroupConfig lobbyGroup() {
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, "Lobby-Server", ServerSoftware.PAPER, "26.3", JavaSelection.AUTO,
                new HeapConfig(MemorySize.parse("256M"), MemorySize.parse("768M")),
                List.of(new CatalogReference("luckperms", CatalogKind.TRANSITIONAL),
                        new CatalogReference("vectora-paper", CatalogKind.PLUGIN)),
                CombatSystem.MODERN);
    }

    /** Dynamic lobby task with scaling. */
    public static TaskConfig lobbyTask() {
        return new TaskConfig(TaskConfig.SCHEMA_VERSION, "lobby", TaskMode.DYNAMIC,
                new ScalingConfig(1, 4, 50, 80, TimeSpan.parse("5m"), TimeSpan.parse("60s")), List.of("node-1"));
    }

    /** Defaults plus the lobby group and task. */
    public static ConfigSnapshot snapshot() {
        return ConfigSnapshot.of(CloudConfig.defaults(), NodesConfig.defaults(), Map.of("lobby", lobbyGroup()),
                Map.of("lobby", lobbyTask()));
    }
}
