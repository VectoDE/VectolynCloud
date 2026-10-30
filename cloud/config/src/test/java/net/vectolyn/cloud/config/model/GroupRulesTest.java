package net.vectolyn.cloud.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The one rule whether a group runs: the node plans by it, the API reports it as {@code runs}. */
class GroupRulesTest {

    private static final CloudConfig LEGACY_ON = CloudConfig.defaults()
            .withLegacyProxy(new LegacyProxyConfig(true, LegacyProxyConfig.defaults().port()));
    private static final CloudConfig LEGACY_OFF = CloudConfig.defaults()
            .withLegacyProxy(new LegacyProxyConfig(false, LegacyProxyConfig.defaults().port()));

    @Test
    void groupSwitchAndKindSwitchMustBothBeOn() {
        GroupConfig lobby = group("Lobby-Server", ServerSoftware.PAPER, null);
        assertTrue(GroupRules.isWanted(lobby, LEGACY_OFF));
        assertFalse(GroupRules.isWanted(group("Lobby-Server", ServerSoftware.PAPER, false), LEGACY_OFF));
        assertFalse(GroupRules.isWanted(lobby, LEGACY_OFF.withServers(WantedServers.all().with(ServerKind.LOBBY, false))));
        assertEquals("cloud.yml servers.lobby", GroupRules.unwantedBecause(lobby,
                LEGACY_OFF.withServers(WantedServers.all().with(ServerKind.LOBBY, false))));
        // A template of the operator's own follows only its own switch.
        assertTrue(GroupRules.isWanted(group("My-Server", ServerSoftware.PAPER, null), LEGACY_OFF.withServers(WantedServers.none())));
        assertFalse(GroupRules.isWanted(group("My-Server", ServerSoftware.PAPER, false), LEGACY_OFF));
    }

    @Test
    void theLegacyPartNeedsTheLegacyProxy() {
        GroupConfig own = group("My-Legacy", ServerSoftware.PANDASPIGOT, null);
        GroupConfig legacyServer = group("Legacy-Server", ServerSoftware.PANDASPIGOT, null);
        GroupConfig legacyProxy = group("Legacy-Proxy", ServerSoftware.VELOCITY, null);
        for (GroupConfig g : List.of(own, legacyServer, legacyProxy)) {
            assertFalse(GroupRules.isWanted(g, LEGACY_OFF), g.template());
            assertEquals("cloud.yml legacy-proxy.enabled", GroupRules.unwantedBecause(g, LEGACY_OFF));
        }
        assertTrue(GroupRules.isWanted(own, LEGACY_ON));
        assertTrue(GroupRules.isWanted(legacyServer, LEGACY_ON));
        assertTrue(GroupRules.isWanted(legacyProxy, LEGACY_ON));
        // PandaSpigot on a template of its own follows servers.legacy-server.
        assertFalse(GroupRules.isWanted(own, LEGACY_ON.withServers(WantedServers.all().with(ServerKind.LEGACY_SERVER, false))));
        // Without the legacy-proxy section the 1.8.8 part is off, like with enabled: false.
        assertFalse(GroupRules.isWanted(legacyServer, LEGACY_ON.withLegacyProxy(null)));
    }

    @Test
    void theDatabaseTemplateNeverRunsAsAService() {
        GroupConfig database = group(ServerKind.DATABASE.templateFolder(), ServerSoftware.PAPER, null);
        assertFalse(GroupRules.isWanted(database, LEGACY_ON));
        assertEquals("the database template runs no service", GroupRules.unwantedBecause(database, LEGACY_ON));
    }

    @Test
    void withoutAServersSectionEverySwitchIsOn() {
        assertTrue(GroupRules.isWanted(group("Lobby-Server", ServerSoftware.PAPER, null), LEGACY_OFF.withServers(null)));
        // Counter-check: the database template stays off and the group switch still counts.
        assertFalse(GroupRules.isWanted(group(ServerKind.DATABASE.templateFolder(), ServerSoftware.PAPER, null),
                LEGACY_OFF.withServers(null)));
        assertFalse(GroupRules.isWanted(group("Lobby-Server", ServerSoftware.PAPER, false), LEGACY_OFF.withServers(null)));
    }

    private static GroupConfig group(String template, ServerSoftware software, Boolean wanted) {
        String version = software == ServerSoftware.PANDASPIGOT ? "1.8.8" : "26.3";
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, version, JavaSelection.AUTO, null, List.of(),
                null, null, wanted);
    }
}
