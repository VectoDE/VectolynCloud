package net.vectora.cloud.download.lock;

import java.util.Optional;

/** Where the checksum in a lock entry comes from. */
public enum Trust {

    /** The source published the checksum (Fill, Modrinth, Hangar, GeyserMC, PandaSpigot, MariaDB, Mojang). */
    SOURCE("source"),

    /**
     * The source publishes no checksum (Fabric server launcher). The SHA-256 of the first download
     * was pinned; every later download must match it.
     */
    TOFU("tofu");

    private final String id;

    Trust(String id) {
        this.id = id;
    }

    /**
     * Returns the name used in the lockfile.
     *
     * @return {@code source} or {@code tofu}
     */
    public String id() {
        return id;
    }

    /**
     * Looks up a value by its lockfile name.
     *
     * @param id the name
     * @return the value, or empty
     */
    public static Optional<Trust> fromId(String id) {
        for (Trust trust : values()) {
            if (trust.id.equals(id)) {
                return Optional.of(trust);
            }
        }
        return Optional.empty();
    }
}
