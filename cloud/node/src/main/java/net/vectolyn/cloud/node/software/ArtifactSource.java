package net.vectolyn.cloud.node.software;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import net.vectolyn.cloud.download.catalog.CatalogEntry;

/**
 * Where {@link SoftwareInstaller} gets the files of catalog entries: verified, pinned in
 * {@code software.lock.yml} and in the artifact cache. {@link LockedDownloads} in the node.
 */
public interface ArtifactSource {

    /**
     * One file, ready to be copied out of the cache.
     *
     * @param lockName    its name in {@code software.lock.yml}
     * @param fileName    the file name at the source, a plain base name
     * @param sha256      the SHA-256 in hex, its address in the cache
     * @param javaMinimum the lowest Java the artifact needs, if a source said so
     */
    record Fetched(String lockName, String fileName, String sha256, OptionalInt javaMinimum) {

        /** Checks the values. */
        public Fetched {
            Objects.requireNonNull(lockName, "lockName");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(javaMinimum, "javaMinimum");
        }
    }

    /**
     * Makes sure the file of a catalog entry is in the cache: the pinned one if
     * {@code software.lock.yml} already has a fitting entry (no request to the source's API, and no
     * network at all once it is cached), otherwise the version the catalog asks for, which is then
     * pinned.
     *
     * @param lockName         name in {@code software.lock.yml}
     * @param entry            the catalog entry
     * @param minecraftVersion the group's Minecraft version
     * @return the file
     * @throws IOException          if it cannot be resolved, downloaded or verified
     * @throws InterruptedException if interrupted
     */
    Fetched fetch(String lockName, CatalogEntry entry, String minecraftVersion) throws IOException, InterruptedException;

    /**
     * Copies a fetched file out of the cache, checking its hash again.
     *
     * @param fetched     the file
     * @param destination the target path
     * @throws IOException if it is not in the cache, changed there, or the copy fails
     */
    void copy(Fetched fetched, Path destination) throws IOException;
}
