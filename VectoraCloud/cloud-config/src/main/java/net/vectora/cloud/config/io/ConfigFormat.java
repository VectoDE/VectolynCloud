package net.vectora.cloud.config.io;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.TaskConfig;
import tools.jackson.databind.node.ObjectNode;

/**
 * One kind of configuration file: its model type, its current {@code schema-version} and the
 * migrations that lead there.
 *
 * <p>Version 1 is the first format of every file, so there are no migrations yet. To change a
 * format later: raise {@code SCHEMA_VERSION} in the model record, change the record, and add a
 * {@link ConfigMigration} from the previous version here.
 *
 * @param <T> model type
 */
public final class ConfigFormat<T> {

    /** Name of the version field in every file. */
    public static final String VERSION_FIELD = "schema-version";

    /** {@code cloud.yml}. */
    public static final ConfigFormat<CloudConfig> CLOUD =
            new ConfigFormat<>(CloudConfig.class, CloudConfig.SCHEMA_VERSION, List.of());
    /** {@code groups/<name>.yml}. */
    public static final ConfigFormat<GroupConfig> GROUP =
            new ConfigFormat<>(GroupConfig.class, GroupConfig.SCHEMA_VERSION, List.of());
    /** {@code tasks/<name>.yml}. */
    public static final ConfigFormat<TaskConfig> TASK =
            new ConfigFormat<>(TaskConfig.class, TaskConfig.SCHEMA_VERSION, List.of());
    /** {@code nodes.yml}. */
    public static final ConfigFormat<NodesConfig> NODES =
            new ConfigFormat<>(NodesConfig.class, NodesConfig.SCHEMA_VERSION, List.of());

    private final Class<T> type;
    private final int currentVersion;
    private final List<ConfigMigration> migrations;

    /**
     * Creates a format. The migrations must form a gapless chain from version 1 to
     * {@code currentVersion}: exactly one migration for each version below the current one.
     */
    public ConfigFormat(Class<T> type, int currentVersion, List<ConfigMigration> migrations) {
        this.type = Objects.requireNonNull(type, "type");
        if (currentVersion < 1) {
            throw new IllegalArgumentException("currentVersion must be at least 1");
        }
        this.currentVersion = currentVersion;
        List<ConfigMigration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(ConfigMigration::fromVersion));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).fromVersion() != i + 1) {
                throw new IllegalArgumentException("migrations must cover versions 1 to " + (currentVersion - 1) + " without gaps");
            }
        }
        if (sorted.size() != currentVersion - 1) {
            throw new IllegalArgumentException("need exactly one migration per version below " + currentVersion);
        }
        this.migrations = List.copyOf(sorted);
    }

    /** Model type. */
    public Class<T> type() {
        return type;
    }

    /** Version this code writes. */
    public int currentVersion() {
        return currentVersion;
    }

    /** Runs every migration from {@code fileVersion} up to the current version, in place. */
    void migrate(ObjectNode root, int fileVersion) {
        for (int version = fileVersion; version < currentVersion; version++) {
            migrations.get(version - 1).migrate(root);
            root.put(VERSION_FIELD, version + 1);
        }
    }
}
