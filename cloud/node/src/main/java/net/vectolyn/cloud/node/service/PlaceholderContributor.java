package net.vectolyn.cloud.node.service;

import java.util.Map;
import net.vectolyn.cloud.config.model.ConfigSnapshot;

/**
 * Adds placeholder values for the templates of a service, for values that belong to another part
 * of the cloud. Example: the database module (stage 2 PR 12) adds {@code VECTOLYN_DB_POOL_SIZE} and
 * {@code VECTOLYN_DB_STORAGE_METHOD}.
 *
 * <p>Values must follow the template rules in {@code docs/cloud/template.md}; the renderer refuses
 * anything else. A contributor cannot override a value the node sets itself (ports, heap, proxy
 * list, secrets); such a key is ignored.
 */
@FunctionalInterface
public interface PlaceholderContributor {

    /**
     * Returns extra values for the service being prepared. The values depend on the configuration
     * only (the database contributor derives the pool size from all tasks), so the service itself
     * is not passed in; add it back once a contributor needs per-service values.
     *
     * @param snapshot the configuration the service was planned with
     * @return placeholder names without braces mapped to their values
     */
    Map<String, String> values(ConfigSnapshot snapshot);
}
