package net.vectora.cloud.config.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ApiConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.PortRange;
import net.vectora.cloud.config.model.ProxyConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.model.TlsConfig;
import org.junit.jupiter.api.Test;

class ConfigOperationsTest {

    private final ConfigSnapshot base = Samples.snapshot();

    @Test
    void cloudSettings() throws ConfigException {
        assertEquals(Language.DE, new SetLanguage(Language.DE).apply(base).cloud().language());
        assertEquals("Vectora Test", new SetNetworkName("Vectora Test").apply(base).cloud().networkName());
        assertTrue(new AcceptEula().apply(base).cloud().eulaAccepted());
        assertTrue(new CompleteSetup().apply(base).cloud().setupComplete());
        assertFalse(new SetServerWanted(ServerKind.MOD, false).apply(base).cloud().servers().mod());
        assertTrue(new SetServerWanted(ServerKind.MOD, false).apply(base).cloud().servers().end());
        ProxyConfig proxy = new ProxyConfig("127.0.0.1", 25577, 19133);
        assertEquals(proxy, new SetProxy(proxy).apply(base).cloud().proxy());
        assertTrue(new SetLegacyProxy(true, 25580).apply(base).cloud().legacyProxy().enabled());
        ApiConfig api = new ApiConfig(true, "127.0.0.1", 9000, TlsConfig.disabled());
        assertEquals(api, new SetApi(api).apply(base).cloud().api());
    }

    @Test
    void databaseModeKeepsTheOtherSettings() throws ConfigException {
        DatabaseConfig docker = new SetDatabaseMode(DatabaseMode.DOCKER).apply(base).cloud().database();
        assertEquals(DatabaseMode.DOCKER, docker.mode());
        assertEquals(DatabaseConfig.DEFAULT_DOCKER_IMAGE, docker.dockerImage());
        assertEquals(3307, docker.port());
        ConfigSnapshot dockerSnapshot = new SetDatabaseMode(DatabaseMode.DOCKER).apply(base);
        assertNull(new SetDatabaseMode(DatabaseMode.EXTERNAL).apply(dockerSnapshot).cloud().database().dockerImage());
        DatabaseConfig external = new DatabaseConfig(DatabaseMode.EXTERNAL, "db.internal", 3306, "vectora", "vectora", null);
        assertEquals(external, new SetDatabase(external).apply(base).cloud().database());
    }

    @Test
    void groupOperations() throws ConfigException {
        ConfigSnapshot heap = new SetGroupHeap("lobby", MemorySize.parse("512M"), MemorySize.parse("1G")).apply(base);
        assertEquals(1024, heap.groups().get("lobby").heap().max().megabytes());
        assertTrue(new PutGroup("survival", Samples.lobbyGroup()).apply(base).groups().containsKey("survival"));
        assertFalse(new RemoveGroup("lobby").apply(base).groups().containsKey("lobby"));
        ConfigException missing = assertThrows(ConfigException.class,
                () -> new SetGroupHeap("nope", MemorySize.parse("1G"), MemorySize.parse("1G")).apply(base));
        assertEquals(ErrorCode.UNKNOWN_GROUP, missing.errors().getFirst().code());
        assertThrows(ConfigException.class, () -> new RemoveGroup("nope").apply(base));
        ConfigException injected = assertThrows(ConfigException.class,
                () -> new SetGroupHeap("x\r\nFAKE \u00e4", MemorySize.parse("1G"), MemorySize.parse("1G")).apply(base));
        assertEquals(List.of("x??FAKE ?"), injected.errors().getFirst().args());
        ConfigException node = assertThrows(ConfigException.class, () -> new RemoveNode("n\u001b").apply(base));
        assertEquals(List.of("n?"), node.errors().getFirst().args());
    }

    @Test
    void taskOperations() throws ConfigException {
        ScalingConfig scaling = new ScalingConfig(2, 10, 16, 75, TimeSpan.parse("10m"), TimeSpan.parse("30s"));
        assertEquals(scaling, new SetTaskScaling("lobby", scaling).apply(base).tasks().get("lobby").scaling());
        assertTrue(new PutTask("lobby-2", Samples.lobbyTask()).apply(base).tasks().containsKey("lobby-2"));
        assertFalse(new RemoveTask("lobby").apply(base).tasks().containsKey("lobby"));
        assertEquals(ErrorCode.UNKNOWN_TASK, assertThrows(ConfigException.class,
                () -> new SetTaskScaling("nope", scaling).apply(base)).errors().getFirst().code());
    }

    @Test
    void nodeOperations() throws ConfigException {
        NodeConfig second = new NodeConfig("node-2", "10.0.0.2", 7010, new PortRange(30000, 30999));
        ConfigSnapshot two = new PutNode(second).apply(base);
        assertEquals(List.of("node-1", "node-2"), two.nodes().nodes().stream().map(NodeConfig::id).toList());
        NodeConfig moved = new NodeConfig("node-2", "10.0.0.3", 7010, new PortRange(30000, 30999));
        assertEquals("10.0.0.3", new PutNode(moved).apply(two).nodes().find("node-2").orElseThrow().host());
        assertEquals(1, new RemoveNode("node-2").apply(two).nodes().nodes().size());
        assertThrows(ConfigException.class, () -> new RemoveNode("node-9").apply(base));
    }

    @Test
    void operationsAreValueObjects() {
        assertEquals(new SetServerWanted(ServerKind.END, true), new SetServerWanted(ServerKind.END, true));
        assertThrows(NullPointerException.class, () -> new SetLanguage(null));
    }
}
