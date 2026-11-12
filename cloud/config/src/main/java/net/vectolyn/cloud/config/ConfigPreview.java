// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config;

import java.util.List;
import java.util.Objects;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * What a change would do, computed by {@link ConfigService#preview} without writing anything.
 *
 * @param before the configuration now
 * @param after  the configuration after the change, already validated
 * @param files  the files that would change, in file name order; empty if nothing changes
 */
public record ConfigPreview(ConfigSnapshot before, ConfigSnapshot after, List<FileChange> files) {

    /** Copies the list. */
    public ConfigPreview {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        files = List.copyOf(files);
    }

    /** Whether the change would leave every file as it is. */
    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** The diffs of every file, one after the other. */
    public String diff() {
        StringBuilder out = new StringBuilder();
        files.forEach(file -> out.append(file.diff()));
        return out.toString();
    }

    /** How a file changes. */
    public enum Kind {
        /** The file is new. */
        ADDED,
        /** The file changes. */
        CHANGED,
        /** The file is deleted. */
        REMOVED
    }

    /**
     * One file of the change.
     *
     * @param file file name relative to {@code config/}, for example {@code groups/lobby.yml}
     * @param kind added, changed or removed
     * @param diff the lines in the unified diff format ({@code diff -u}), LF line ends. The old side is the
     *             file as the cloud would write the current configuration, so comments of a hand-edited file
     *             do not show up in it.
     */
    public record FileChange(String file, Kind kind, String diff) {

        /** Checks the values. */
        public FileChange {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(diff, "diff");
        }
    }
}
