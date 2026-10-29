package net.vectora.cloud.setup;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.secret.Secret;

/**
 * Everything the assistant decides, whether the answers come from the console or from a setup file.
 * Both ways end here, so both are checked by {@link SetupChecks} and written by {@link SetupApplier}
 * the same way.
 *
 * @param language         language of console and messages
 * @param eulaAccepted     whether the operator accepted the Minecraft EULA
 * @param networkName      display name of the network
 * @param servers          the "wanted" switch of every {@link NetworkServer}
 * @param groupSwitches    the group's own switch, by group name; used for minigame groups
 * @param bind             address the proxies bind to
 * @param javaPort         Java port of the main proxy
 * @param bedrockPort      Bedrock port (Geyser) of the main proxy
 * @param legacyPort       Java port of the legacy proxy
 * @param heap             maximum heap per group name
 * @param database         database settings for {@code cloud.yml}
 * @param databasePassword a new password of an external database for {@code VECTORA_DB_PASSWORD};
 *                         empty keeps what {@code secrets/vectora.env} holds
 */
public record SetupAnswers(Language language, boolean eulaAccepted, String networkName,
                           Map<NetworkServer, Boolean> servers, Map<String, Boolean> groupSwitches,
                           String bind, int javaPort, int bedrockPort, int legacyPort,
                           Map<String, MemorySize> heap, DatabaseConfig database, Optional<Secret> databasePassword) {

    /** Copies the maps; every server has a switch. */
    public SetupAnswers {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(networkName, "networkName");
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(databasePassword, "databasePassword");
        EnumMap<NetworkServer, Boolean> copy = new EnumMap<>(NetworkServer.class);
        for (NetworkServer server : NetworkServer.values()) {
            copy.put(server, Boolean.TRUE.equals(Objects.requireNonNull(servers, "servers").get(server)));
        }
        servers = Collections.unmodifiableMap(copy);
        groupSwitches = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(groupSwitches, "groupSwitches")));
        heap = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(heap, "heap")));
    }

    /** Whether {@code server} is wanted. */
    public boolean wants(NetworkServer server) {
        return servers.get(server);
    }

    /** A copy with another language. */
    public SetupAnswers withLanguage(Language value) {
        return new SetupAnswers(value, eulaAccepted, networkName, servers, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, heap, database, databasePassword);
    }

    /** A copy with the EULA answer. */
    public SetupAnswers withEula(boolean value) {
        return new SetupAnswers(language, value, networkName, servers, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, heap, database, databasePassword);
    }

    /** A copy with another network name. */
    public SetupAnswers withNetworkName(String value) {
        return new SetupAnswers(language, eulaAccepted, value, servers, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, heap, database, databasePassword);
    }

    /** A copy with one switch changed. */
    public SetupAnswers withServer(NetworkServer server, boolean wanted) {
        EnumMap<NetworkServer, Boolean> copy = new EnumMap<>(servers);
        copy.put(server, wanted);
        return new SetupAnswers(language, eulaAccepted, networkName, copy, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, heap, database, databasePassword);
    }

    /** A copy with the switch of one group changed. */
    public SetupAnswers withGroupSwitch(String group, boolean wanted) {
        Map<String, Boolean> copy = new TreeMap<>(groupSwitches);
        copy.put(group, wanted);
        return new SetupAnswers(language, eulaAccepted, networkName, servers, copy, bind, javaPort, bedrockPort,
                legacyPort, heap, database, databasePassword);
    }

    /** A copy with another listener. */
    public SetupAnswers withNetwork(String newBind, int newJavaPort, int newBedrockPort, int newLegacyPort) {
        return new SetupAnswers(language, eulaAccepted, networkName, servers, groupSwitches, newBind, newJavaPort,
                newBedrockPort, newLegacyPort, heap, database, databasePassword);
    }

    /** A copy with the heap of one group changed. */
    public SetupAnswers withHeap(String group, MemorySize max) {
        Map<String, MemorySize> copy = new TreeMap<>(heap);
        copy.put(group, max);
        return new SetupAnswers(language, eulaAccepted, networkName, servers, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, copy, database, databasePassword);
    }

    /** A copy with other database settings. */
    public SetupAnswers withDatabase(DatabaseConfig value, Optional<Secret> password) {
        return new SetupAnswers(language, eulaAccepted, networkName, servers, groupSwitches, bind, javaPort, bedrockPort,
                legacyPort, heap, value, password);
    }

    /** Never shows the password, only whether a new one is set. */
    @Override
    public String toString() {
        return "SetupAnswers[language=" + language + ", eula=" + eulaAccepted + ", networkName=" + networkName
                + ", servers=" + servers + ", groupSwitches=" + groupSwitches + ", bind=" + bind + ", javaPort=" + javaPort
                + ", bedrockPort=" + bedrockPort + ", legacyPort=" + legacyPort + ", heap=" + heap + ", database=" + database
                + ", databasePassword=" + (databasePassword.isPresent() ? Secret.MASK : "unchanged") + "]";
    }
}
