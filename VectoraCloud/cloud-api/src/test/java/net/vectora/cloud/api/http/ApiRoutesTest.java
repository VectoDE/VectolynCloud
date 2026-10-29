package net.vectora.cloud.api.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.LegacyProxyConfig;
import net.vectora.cloud.config.model.ServerKind;
import net.vectora.cloud.config.model.ServerSoftware;
import net.vectora.cloud.config.model.WantedServers;
import org.junit.jupiter.api.Test;

/** The "runs" rule of the wanted endpoints, which must match {@code ServicePlanner.isWanted} of cloud-node. */
class ApiRoutesTest {

    private static final CloudConfig LEGACY_ON = CloudConfig.defaults()
            .withLegacyProxy(new LegacyProxyConfig(true, LegacyProxyConfig.defaults().port()));
    private static final CloudConfig LEGACY_OFF = CloudConfig.defaults()
            .withLegacyProxy(new LegacyProxyConfig(false, LegacyProxyConfig.defaults().port()));

    @Test
    void groupSwitchAndKindSwitchMustBothBeOn() {
        GroupConfig lobby = group("Lobby-Server", ServerSoftware.PAPER, null);
        assertTrue(ApiRoutes.runs(lobby, LEGACY_OFF));
        assertFalse(ApiRoutes.runs(group("Lobby-Server", ServerSoftware.PAPER, false), LEGACY_OFF));
        assertFalse(ApiRoutes.runs(lobby, LEGACY_OFF.withServers(WantedServers.all().with(ServerKind.LOBBY, false))));
        // A template of the operator's own follows only its own switch.
        assertTrue(ApiRoutes.runs(group("My-Server", ServerSoftware.PAPER, null), LEGACY_OFF.withServers(WantedServers.none())));
        assertFalse(ApiRoutes.runs(group("My-Server", ServerSoftware.PAPER, false), LEGACY_OFF));
    }

    @Test
    void theLegacyPartNeedsTheLegacyProxy() {
        GroupConfig own = group("My-Legacy", ServerSoftware.PANDASPIGOT, null);
        GroupConfig legacyServer = group("Legacy-Server", ServerSoftware.PANDASPIGOT, null);
        GroupConfig legacyProxy = group("Legacy-Proxy", ServerSoftware.VELOCITY, null);
        for (GroupConfig g : List.of(own, legacyServer, legacyProxy)) {
            assertFalse(ApiRoutes.runs(g, LEGACY_OFF), g.template());
        }
        assertTrue(ApiRoutes.runs(own, LEGACY_ON));
        assertTrue(ApiRoutes.runs(legacyServer, LEGACY_ON));
        assertTrue(ApiRoutes.runs(legacyProxy, LEGACY_ON));
        // PandaSpigot on a template of its own follows servers.legacy-server.
        assertFalse(ApiRoutes.runs(own, LEGACY_ON.withServers(WantedServers.all().with(ServerKind.LEGACY_SERVER, false))));
    }

    @Test
    void theDatabaseTemplateNeverRunsAsAService() {
        assertFalse(ApiRoutes.runs(group(ServerKind.DATABASE.templateFolder(), ServerSoftware.PAPER, null), LEGACY_ON));
    }

    private static GroupConfig group(String template, ServerSoftware software, Boolean wanted) {
        String version = software == ServerSoftware.PANDASPIGOT ? "1.8.8" : "26.3";
        return new GroupConfig(GroupConfig.SCHEMA_VERSION, template, software, version, JavaSelection.AUTO, null, List.of(),
                null, null, wanted);
    }
}
