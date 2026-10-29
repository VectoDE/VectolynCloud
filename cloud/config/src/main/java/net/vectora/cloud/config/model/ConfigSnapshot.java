package net.vectora.cloud.config.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The whole configuration at one point in time: {@code cloud.yml}, {@code nodes.yml}, every file in
 * {@code groups/} and {@code tasks/}. Immutable; changes produce a new snapshot.
 *
 * @param cloud  {@code cloud.yml}
 * @param nodes  {@code nodes.yml}
 * @param groups groups by name (file name without {@code .yml}), sorted
 * @param tasks  tasks by name, sorted
 */
public record ConfigSnapshot(CloudConfig cloud, NodesConfig nodes, SortedMap<String, GroupConfig> groups,
                             SortedMap<String, TaskConfig> tasks) {

    /** Copies the maps into unmodifiable sorted maps. */
    public ConfigSnapshot {
        Objects.requireNonNull(cloud, "cloud");
        Objects.requireNonNull(nodes, "nodes");
        groups = Collections.unmodifiableSortedMap(new TreeMap<>(groups));
        tasks = Collections.unmodifiableSortedMap(new TreeMap<>(tasks));
    }

    /** Snapshot from plain maps. */
    public static ConfigSnapshot of(CloudConfig cloud, NodesConfig nodes, Map<String, GroupConfig> groups,
                                    Map<String, TaskConfig> tasks) {
        return new ConfigSnapshot(cloud, nodes, new TreeMap<>(groups), new TreeMap<>(tasks));
    }

    /** Default {@code cloud.yml} and {@code nodes.yml}, no groups and no tasks. */
    public static ConfigSnapshot defaults() {
        return of(CloudConfig.defaults(), NodesConfig.defaults(), Map.of(), Map.of());
    }

    /** A copy with another {@code cloud.yml}. */
    public ConfigSnapshot withCloud(CloudConfig value) {
        return new ConfigSnapshot(value, nodes, groups, tasks);
    }

    /** A copy with another {@code nodes.yml}. */
    public ConfigSnapshot withNodes(NodesConfig value) {
        return new ConfigSnapshot(cloud, value, groups, tasks);
    }

    /** A copy where group {@code name} is added or replaced. */
    public ConfigSnapshot withGroup(String name, GroupConfig group) {
        SortedMap<String, GroupConfig> copy = new TreeMap<>(groups);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(group, "group"));
        return new ConfigSnapshot(cloud, nodes, copy, tasks);
    }

    /** A copy without group {@code name}. */
    public ConfigSnapshot withoutGroup(String name) {
        SortedMap<String, GroupConfig> copy = new TreeMap<>(groups);
        copy.remove(name);
        return new ConfigSnapshot(cloud, nodes, copy, tasks);
    }

    /** A copy where task {@code name} is added or replaced. */
    public ConfigSnapshot withTask(String name, TaskConfig task) {
        SortedMap<String, TaskConfig> copy = new TreeMap<>(tasks);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(task, "task"));
        return new ConfigSnapshot(cloud, nodes, groups, copy);
    }

    /** A copy without task {@code name}. */
    public ConfigSnapshot withoutTask(String name) {
        SortedMap<String, TaskConfig> copy = new TreeMap<>(tasks);
        copy.remove(name);
        return new ConfigSnapshot(cloud, nodes, groups, copy);
    }
}
