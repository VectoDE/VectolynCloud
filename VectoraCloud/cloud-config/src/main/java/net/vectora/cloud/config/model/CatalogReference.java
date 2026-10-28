package net.vectora.cloud.config.model;

/**
 * A plugin or mod a group installs, by its id in the download catalog. Version and checksum come
 * from the catalog and {@code software.lock.yml}, not from the group.
 *
 * @param id   catalog id, for example {@code luckperms}
 * @param kind plugin, mod or transitional
 */
public record CatalogReference(String id, CatalogKind kind) {
}
