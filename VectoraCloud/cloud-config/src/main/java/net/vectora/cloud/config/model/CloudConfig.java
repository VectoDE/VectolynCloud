package net.vectora.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Content of {@code <home>/cloud.yml}: the settings of the whole network and of this node.
 *
 * @param schemaVersion version of the file format, see {@link #SCHEMA_VERSION}
 * @param networkName   display name of the network
 * @param language      language of console, setup assistant and messages
 * @param eulaAccepted  whether the operator accepted the Minecraft EULA ({@code eula.txt} is written only then)
 * @param setupComplete whether the setup assistant has finished
 * @param nodeId        id of this node, must exist in {@code nodes.yml}
 * @param servers       which servers are wanted
 * @param proxy         main proxy listener
 * @param legacyProxy   legacy proxy for the 1.8.8 groups
 * @param database      database server
 * @param api           REST and WebSocket API
 */
@JsonPropertyOrder({"schema-version", "network-name", "language", "eula-accepted", "setup-complete", "node-id",
        "servers", "proxy", "legacy-proxy", "database", "api"})
public record CloudConfig(int schemaVersion, String networkName, Language language, boolean eulaAccepted,
                          boolean setupComplete, String nodeId, WantedServers servers, ProxyConfig proxy,
                          LegacyProxyConfig legacyProxy, DatabaseConfig database, ApiConfig api) {

    /** Current version of the {@code cloud.yml} format. */
    public static final int SCHEMA_VERSION = 1;

    /** Settings before the setup assistant has run. */
    public static CloudConfig defaults() {
        return new CloudConfig(SCHEMA_VERSION, "Vectora", Language.EN, false, false, NodeConfig.DEFAULT_ID,
                WantedServers.all(), ProxyConfig.defaults(), LegacyProxyConfig.defaults(), DatabaseConfig.defaults(),
                ApiConfig.defaults());
    }

    /** A copy with another network name. */
    public CloudConfig withNetworkName(String value) {
        return new CloudConfig(schemaVersion, value, language, eulaAccepted, setupComplete, nodeId, servers, proxy,
                legacyProxy, database, api);
    }

    /** A copy with another language. */
    public CloudConfig withLanguage(Language value) {
        return new CloudConfig(schemaVersion, networkName, value, eulaAccepted, setupComplete, nodeId, servers, proxy,
                legacyProxy, database, api);
    }

    /** A copy with the EULA switch set. */
    public CloudConfig withEulaAccepted(boolean value) {
        return new CloudConfig(schemaVersion, networkName, language, value, setupComplete, nodeId, servers, proxy,
                legacyProxy, database, api);
    }

    /** A copy with the setup switch set. */
    public CloudConfig withSetupComplete(boolean value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, value, nodeId, servers, proxy,
                legacyProxy, database, api);
    }

    /** A copy with another node id. */
    public CloudConfig withNodeId(String value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, value, servers, proxy,
                legacyProxy, database, api);
    }

    /** A copy with other wanted servers. */
    public CloudConfig withServers(WantedServers value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, nodeId, value, proxy,
                legacyProxy, database, api);
    }

    /** A copy with another proxy listener. */
    public CloudConfig withProxy(ProxyConfig value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, nodeId, servers, value,
                legacyProxy, database, api);
    }

    /** A copy with other legacy proxy settings. */
    public CloudConfig withLegacyProxy(LegacyProxyConfig value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, nodeId, servers, proxy,
                value, database, api);
    }

    /** A copy with another database. */
    public CloudConfig withDatabase(DatabaseConfig value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, nodeId, servers, proxy,
                legacyProxy, value, api);
    }

    /** A copy with other API settings. */
    public CloudConfig withApi(ApiConfig value) {
        return new CloudConfig(schemaVersion, networkName, language, eulaAccepted, setupComplete, nodeId, servers, proxy,
                legacyProxy, database, value);
    }
}
