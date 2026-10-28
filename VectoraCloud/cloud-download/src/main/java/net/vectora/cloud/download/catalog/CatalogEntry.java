package net.vectora.cloud.download.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectora.cloud.download.provider.ArtifactRequest;

/**
 * One artifact of the catalog.
 *
 * @param id unique name, also the name in {@code software.lock.yml}
 * @param kind what it is
 * @param type standard or transitional
 * @param provider the provider id
 * @param project the project at the provider
 * @param platform loader, download key or Hangar platform, or {@code null}
 * @param gameVersion how the group's Minecraft version is used
 * @param targets server templates that use it
 * @param replacedBy for transitional entries: what replaces them, otherwise {@code null}
 * @param alternative a second source as {@code provider:project}, or {@code null}
 */
public record CatalogEntry(
        String id,
        Kind kind,
        Type type,
        String provider,
        String project,
        String platform,
        GameVersionUse gameVersion,
        List<String> targets,
        String replacedBy,
        String alternative) {

    /** What an artifact is. */
    public enum Kind {
        /** Server or proxy software. */
        SERVER,
        /** A plugin for Paper or Velocity. */
        PLUGIN,
        /** A Fabric mod. */
        MOD,
        /** A database server. */
        DATABASE
    }

    /** Whether an artifact stays. */
    public enum Type {
        /** Part of the network for good. */
        STANDARD,
        /** A third-party plugin that is removed once the Vectora replacement exists. */
        TRANSITIONAL
    }

    /** How the Minecraft version of a group goes into the request. */
    public enum GameVersionUse {
        /** It is the version to resolve (Paper). */
        VERSION,
        /** It filters the versions (Modrinth, Fabric). */
        FILTER,
        /** Not used; the newest release is resolved. */
        NONE
    }

    /**
     * Checks the values.
     *
     * @param id the id
     * @param kind the kind
     * @param type the type
     * @param provider the provider id
     * @param project the project
     * @param platform the platform or {@code null}
     * @param gameVersion how the Minecraft version is used
     * @param targets the templates
     * @param replacedBy the replacement or {@code null}
     * @param alternative the alternative source or {@code null}
     */
    public CatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(gameVersion, "gameVersion");
        targets = List.copyOf(targets);
        if (type == Type.TRANSITIONAL && (replacedBy == null || replacedBy.isBlank())) {
            throw new IllegalArgumentException(id + ": a transitional entry must say what replaces it");
        }
    }

    /**
     * Tells whether the entry is transitional.
     *
     * @return {@code true} for {@link Type#TRANSITIONAL}
     */
    public boolean transitional() {
        return type == Type.TRANSITIONAL;
    }

    /**
     * Returns the alternative source.
     *
     * @return {@code provider:project}, or empty
     */
    public Optional<String> alternativeOpt() {
        return Optional.ofNullable(alternative);
    }

    /**
     * Builds the request for a group.
     *
     * @param minecraftVersion the group's Minecraft version, or {@code null} if it has none (proxy)
     * @return the request for the newest matching version
     */
    public ArtifactRequest toRequest(String minecraftVersion) {
        ArtifactRequest request = ArtifactRequest.of(project).withPlatform(platform);
        if (minecraftVersion != null) {
            request = switch (gameVersion) {
                case VERSION -> request.withVersion(minecraftVersion);
                case FILTER -> request.withGameVersion(minecraftVersion);
                case NONE -> request;
            };
        }
        return request;
    }
}
