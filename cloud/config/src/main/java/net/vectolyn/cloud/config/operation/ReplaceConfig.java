// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.operation;

import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Replaces the whole configuration ({@code cloud.yml}, {@code nodes.yml}, every group and task)
 * with another one, for example an imported archive. Groups and tasks that the new configuration
 * does not have are deleted.
 *
 * @param snapshot the new configuration
 */
public record ReplaceConfig(ConfigSnapshot snapshot) implements ConfigOperation {

    /** Checks the argument. */
    public ReplaceConfig {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public ConfigSnapshot apply(ConfigSnapshot current) {
        return snapshot;
    }
}
