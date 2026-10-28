package net.vectora.cloud.node.service;

import java.util.Map;
import net.vectora.cloud.config.model.ConfigSnapshot;

/**
 * Adds placeholder values for the templates of a service, for values that belong to another part
 * of the cloud. Example: the database module (stage 2 PR 12) adds {@code VECTORA_DB_POOL_SIZE} and
 * {@code VECTORA_DB_STORAGE_METHOD}.
 *
 * <p>Values must follow the template rules in {@code docs/cloud/template.md}; the renderer refuses
 * anything else. A contributor cannot override a value the node sets itself (ports, heap, proxy
 * list, secrets); such a key is ignored.
 */
@FunctionalInterface
public interface PlaceholderContributor {

    /**
     * Returns extra values for one service.
     *
     * @param service  the service being prepared
     * @param snapshot the configuration it was planned with
     * @return placeholder names without braces mapped to their values
     */
    Map<String, String> values(ServiceSpec service, ConfigSnapshot snapshot);
}
