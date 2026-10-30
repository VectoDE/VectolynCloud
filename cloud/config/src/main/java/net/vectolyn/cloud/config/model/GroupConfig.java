package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Content of {@code <home>/groups/<name>.yml}: what a service of the group runs. The group name is
 * the file name, so it cannot disagree with the file.
 *
 * @param schemaVersion    version of the file format, see {@link #SCHEMA_VERSION}
 * @param template         template folder in the cloud home, for example {@code Lobby-Server}
 * @param software         server software
 * @param minecraftVersion Minecraft version, for example {@code 26.3} or {@code 1.8.8}; quote it in YAML
 * @param java             Java major version or {@code auto}
 * @param heap             heap of each service
 * @param catalog          plugins and mods from the download catalog, never null
 * @param combat           combat mechanics
 * @param process          how the node runs each service (JVM flags, stop, restart); null means
 *                         {@link ProcessConfig#defaults()} and is not written to the file
 * @param wanted           the group's own "wanted" switch, for example one minigame out of several;
 *                         null means wanted and is not written. A group runs only if both this and
 *                         the switch of its server kind in {@code cloud.yml} are on.
 * @param drainTimeout     how long a service of the group may take to send its players elsewhere
 *                         before the scaler stops it, {@link #MIN_DRAIN_TIMEOUT_SECONDS} to
 *                         {@link #MAX_DRAIN_TIMEOUT_SECONDS} seconds; null means
 *                         {@link #DEFAULT_DRAIN_TIMEOUT} and is not written
 */
@JsonPropertyOrder({"schema-version", "template", "software", "minecraft-version", "java", "heap", "combat", "wanted",
        "drain-timeout", "catalog", "process"})
public record GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                          JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat,
                          ProcessConfig process, Boolean wanted, TimeSpan drainTimeout) {

    /** Current version of the group file format. */
    public static final int SCHEMA_VERSION = 1;
    /** {@code drain-timeout} of a group without the field. */
    public static final TimeSpan DEFAULT_DRAIN_TIMEOUT = TimeSpan.ofSeconds(30);
    /** Shortest {@code drain-timeout} in seconds. */
    public static final long MIN_DRAIN_TIMEOUT_SECONDS = 5;
    /** Longest {@code drain-timeout} in seconds (10 minutes). */
    public static final long MAX_DRAIN_TIMEOUT_SECONDS = 600;

    /** Copies the catalog list; a missing list becomes empty. */
    public GroupConfig {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }

    /** A group without a {@code process} section, without its own switch and drain timeout. */
    public GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                       JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat) {
        this(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, null, null, null);
    }

    /** A group without its own switch and drain timeout. */
    public GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                       JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat,
                       ProcessConfig process) {
        this(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, process, null, null);
    }

    /** A group without its own drain timeout. */
    public GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                       JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat,
                       ProcessConfig process, Boolean wanted) {
        this(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, process, wanted, null);
    }

    /** The {@code process} section, or {@link ProcessConfig#defaults()} if the file has none. */
    public ProcessConfig processOrDefaults() {
        return process == null ? ProcessConfig.defaults() : process;
    }

    /** The group's own switch: true unless the file says {@code wanted: false}. */
    public boolean wantedOrDefault() {
        return !Boolean.FALSE.equals(wanted);
    }

    /** {@code drain-timeout}, or {@link #DEFAULT_DRAIN_TIMEOUT} if the file has none. */
    public TimeSpan drainTimeoutOrDefault() {
        return drainTimeout == null ? DEFAULT_DRAIN_TIMEOUT : drainTimeout;
    }

    /** A copy with another {@code process} section; null removes it. */
    public GroupConfig withProcess(ProcessConfig value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, value, wanted,
                drainTimeout);
    }

    /** A copy with another heap. */
    public GroupConfig withHeap(HeapConfig value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, value, catalog, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with another catalog list. */
    public GroupConfig withCatalog(List<CatalogReference> value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, value, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with other combat mechanics. */
    public GroupConfig withCombat(CombatSystem value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, value, process, wanted,
                drainTimeout);
    }

    /** A copy with another template folder. */
    public GroupConfig withTemplate(String value) {
        return new GroupConfig(schemaVersion, value, software, minecraftVersion, java, heap, catalog, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with other server software. */
    public GroupConfig withSoftware(ServerSoftware value) {
        return new GroupConfig(schemaVersion, template, value, minecraftVersion, java, heap, catalog, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with another Minecraft version. */
    public GroupConfig withMinecraftVersion(String value) {
        return new GroupConfig(schemaVersion, template, software, value, java, heap, catalog, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with another Java selection. */
    public GroupConfig withJava(JavaSelection value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, value, heap, catalog, combat, process, wanted,
                drainTimeout);
    }

    /** A copy with another {@code drain-timeout}; null removes it (the default applies). */
    public GroupConfig withDrainTimeout(TimeSpan value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, process, wanted,
                value);
    }

    /**
     * A copy with the group's own switch set. {@code true} removes the entry, because wanted is the
     * default, so the file only says something when a group is switched off.
     */
    public GroupConfig withWanted(boolean value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, process,
                value ? null : Boolean.FALSE, drainTimeout);
    }
}
