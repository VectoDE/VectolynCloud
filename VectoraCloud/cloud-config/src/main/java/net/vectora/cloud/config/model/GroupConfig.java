package net.vectora.cloud.config.model;

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
 */
@JsonPropertyOrder({"schema-version", "template", "software", "minecraft-version", "java", "heap", "combat", "catalog",
        "process"})
public record GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                          JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat,
                          ProcessConfig process) {

    /** Current version of the group file format. */
    public static final int SCHEMA_VERSION = 1;

    /** Copies the catalog list; a missing list becomes empty. */
    public GroupConfig {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }

    /** A group without a {@code process} section, i.e. with {@link ProcessConfig#defaults()}. */
    public GroupConfig(int schemaVersion, String template, ServerSoftware software, String minecraftVersion,
                       JavaSelection java, HeapConfig heap, List<CatalogReference> catalog, CombatSystem combat) {
        this(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, null);
    }

    /** The {@code process} section, or {@link ProcessConfig#defaults()} if the file has none. */
    public ProcessConfig processOrDefaults() {
        return process == null ? ProcessConfig.defaults() : process;
    }

    /** A copy with another {@code process} section; null removes it. */
    public GroupConfig withProcess(ProcessConfig value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, combat, value);
    }

    /** A copy with another heap. */
    public GroupConfig withHeap(HeapConfig value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, value, catalog, combat, process);
    }

    /** A copy with another catalog list. */
    public GroupConfig withCatalog(List<CatalogReference> value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, value, combat, process);
    }

    /** A copy with other combat mechanics. */
    public GroupConfig withCombat(CombatSystem value) {
        return new GroupConfig(schemaVersion, template, software, minecraftVersion, java, heap, catalog, value, process);
    }
}
