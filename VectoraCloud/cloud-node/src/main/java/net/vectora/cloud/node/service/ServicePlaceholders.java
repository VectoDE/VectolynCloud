package net.vectora.cloud.node.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.secret.EnvFile;
import net.vectora.cloud.template.PlaceholderValues;
import net.vectora.cloud.template.ProxyBackend;
import net.vectora.cloud.template.ProxyServerList;
import net.vectora.cloud.template.ServerPlatform;

/**
 * Builds the {@code {{VECTORA_*}}} values for one service, as listed in
 * {@code Server-Network/placeholders.md}:
 * <ul>
 *   <li>secrets from {@code secrets/vectora.env}, only those the platform of the service needs
 *       ({@link ServiceSecrets}); {@code VECTORA_DB_ROOT_PASSWORD} never;</li>
 *   <li>database host, port, name and user from {@code cloud.yml};</li>
 *   <li>per service: {@code VECTORA_SERVER_PORT}, {@code VECTORA_SERVER_HOST}, {@code VECTORA_XMS},
 *       {@code VECTORA_XMX};</li>
 *   <li>for the proxy: bind, port, Bedrock port and the server list built from the backends; for
 *       the legacy proxy also {@code VECTORA_LEGACY_PROXY_PORT}.</li>
 * </ul>
 * Values of {@link PlaceholderContributor}s come last and cannot replace any of the above.
 */
final class ServicePlaceholders {

    private ServicePlaceholders() {
    }

    /**
     * Builds the values.
     *
     * @param spec         the service
     * @param platform     its platform; decides which secrets it sees
     * @param snapshot     the configuration
     * @param node         this node
     * @param secrets      the secrets file
     * @param backends     backends the proxy lists (only used for a proxy)
     * @param tryOrder     names the proxy tries on login, in order
     * @param contributors extra values
     * @return the placeholder source
     * @throws ServiceException if the proxy list cannot be built from the backends, or a secret the
     *                          platform needs is missing in {@code secrets/vectora.env}
     */
    static PlaceholderValues build(ServiceSpec spec, ServerPlatform platform, ConfigSnapshot snapshot, NodeConfig node,
                                   EnvFile secrets, Collection<ProxyBackend> backends, List<String> tryOrder,
                                   List<PlaceholderContributor> contributors) throws ServiceException {
        Map<String, String> values = new HashMap<>(ServiceSecrets.placeholders(platform, secrets));
        DatabaseConfig db = snapshot.cloud().database();
        values.put("VECTORA_DB_HOST", db.host());
        values.put("VECTORA_DB_PORT", Integer.toString(db.port()));
        values.put("VECTORA_DB_NAME", db.name());
        values.put("VECTORA_DB_USER", db.user());
        values.put("VECTORA_SERVER_HOST", node.host());
        values.put("VECTORA_SERVER_PORT", Integer.toString(spec.port()));
        values.put("VECTORA_XMS", spec.group().heap().min().toString());
        values.put("VECTORA_XMX", spec.group().heap().max().toString());
        PlaceholderValues source = PlaceholderValues.of(values);
        if (spec.isProxy()) {
            ProxyConfig proxy = snapshot.cloud().proxy();
            Map<String, String> proxyValues = new HashMap<>();
            proxyValues.put("VECTORA_PROXY_BIND", proxy.bind());
            proxyValues.put("VECTORA_PROXY_PORT", Integer.toString(proxy.port()));
            proxyValues.put("VECTORA_BEDROCK_PORT", Integer.toString(proxy.bedrockPort()));
            if (spec.isLegacyProxy()) {
                proxyValues.put("VECTORA_LEGACY_PROXY_PORT", Integer.toString(spec.port()));
            }
            ProxyServerList list;
            try {
                list = ProxyServerList.of(backends, tryOrder, Map.of());
            } catch (IllegalArgumentException e) {
                throw new ServiceException("cannot build the server list of " + spec.id() + ": " + e.getMessage(), e);
            }
            source = PlaceholderValues.of(proxyValues).orElse(source).orElse(list.placeholderValues());
        }
        for (PlaceholderContributor contributor : contributors) {
            source = source.orElse(PlaceholderValues.of(contributor.values(spec, snapshot)));
        }
        return source;
    }
}
