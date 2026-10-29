package net.vectora.cloud.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.operation.ConfigOperation;

/**
 * The configuration changed.
 *
 * @param previous   snapshot before the change
 * @param current    snapshot after the change, already on disk
 * @param files      files that were written or deleted, relative to the home ({@code groups/lobby.yml})
 * @param origin     what caused the change
 * @param operations the operations applied, in order; empty for {@link Origin#FILE}
 */
public record ConfigChanged(ConfigSnapshot previous, ConfigSnapshot current, Set<String> files, Origin origin,
                            List<ConfigOperation> operations) {

    /** What caused a change. */
    public enum Origin {
        /** {@code ConfigService.apply} (console, setup assistant, API). */
        OPERATION,
        /** A file was edited by hand and reloaded. */
        FILE
    }

    /** Copies the collections. */
    public ConfigChanged {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(origin, "origin");
        files = Set.copyOf(files);
        operations = List.copyOf(operations);
    }

    /** Whether anything changed at all. */
    public boolean isEmpty() {
        return files.isEmpty();
    }
}
