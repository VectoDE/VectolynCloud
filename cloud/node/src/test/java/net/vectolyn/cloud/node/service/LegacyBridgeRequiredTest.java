package net.vectolyn.cloud.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.vectolyn.cloud.config.model.CombatSystem;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.HeapConfig;
import net.vectolyn.cloud.config.model.JavaSelection;
import net.vectolyn.cloud.config.model.MemorySize;
import net.vectolyn.cloud.config.model.ServerSoftware;
import net.vectolyn.cloud.config.operation.PutGroup;
import net.vectolyn.cloud.config.operation.SetLegacyProxy;
import net.vectolyn.cloud.node.software.BridgePlugins;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A PandaSpigot server runs with bungeecord: true, so it never starts without the legacy bridge. */
class LegacyBridgeRequiredTest {

    @TempDir
    Path dir;

    private static void legacyGroup(TestNetwork net) throws Exception {
        net.file("local/templates/Legacy-Server/default/server.properties", "server-port={{VECTOLYN_SERVER_PORT}}\n");
        GroupConfig group = new GroupConfig(GroupConfig.SCHEMA_VERSION, "Legacy-Server", ServerSoftware.PANDASPIGOT,
                "1.8.8", JavaSelection.AUTO, new HeapConfig(MemorySize.parse("16M"), MemorySize.parse("64M")), List.of(),
                CombatSystem.LEGACY, TestNetwork.process("normal", "20s", TestNetwork.noRestart()));
        net.config.apply(List.of(new SetLegacyProxy(true, 25577), new PutGroup("legacy", group)));
        net.task("legacy", "legacy", 1, 1, 1);
    }

    @Test
    void withoutTheLegacyBridgeThePandaSpigotServerIsNotStarted() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            legacyGroup(net);
            // The jar only, as a provider that forgets the bridge would leave it.
            try (ServiceManager manager = net.manager().build()) {
                ServiceException refused = assertThrows(ServiceException.class, () -> manager.start("legacy"));
                assertTrue(refused.getMessage().contains("VectolynCloudBridgeLegacy"), refused.getMessage());
                assertEquals(ServiceState.CRASHED, manager.find(new ServiceId("legacy-1")).orElseThrow().state());
                assertTrue(manager.find(new ServiceId("legacy-1")).orElseThrow().pid().isEmpty(), "no process");
            }
        }
    }

    @Test
    void aBridgeOfAnotherPlatformDoesNotCount() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            legacyGroup(net);
            ServerSoftwareProvider jar = net.fakeSoftware();
            ServerSoftwareProvider paperBridge = (spec, folder) -> {
                BridgePlugins.fromClassPath().install(BridgePlugins.Bridge.PAPER, folder.resolve("plugins"));
                return jar.provide(spec, folder);
            };
            try (ServiceManager manager = net.manager().serverSoftware(paperBridge).build()) {
                assertThrows(ServiceException.class, () -> manager.start("legacy"));
            }
        }
    }

    @Test
    void counterCheckWithTheBridgeItStarts() throws Exception {
        try (TestNetwork net = TestNetwork.create(dir)) {
            legacyGroup(net);
            ServerSoftwareProvider jar = net.fakeSoftware();
            ServerSoftwareProvider withBridge = (spec, folder) -> {
                BridgePlugins.fromClassPath().install(BridgePlugins.Bridge.LEGACY, folder.resolve("plugins"));
                return jar.provide(spec, folder);
            };
            try (ServiceManager manager = net.manager().serverSoftware(withBridge).build()) {
                ServiceInfo started = manager.start("legacy");
                assertTrue(started.state().hasProcess(), started.state().toString());
                try (var plugins = Files.list(started.workDirectory().resolve("plugins"))) {
                    assertTrue(plugins.anyMatch(p -> BridgePlugins.Bridge.LEGACY.matches(p.getFileName().toString())));
                }
                manager.stop(started.id());
                TestNetwork.await("stopped", Duration.ofSeconds(30),
                        () -> manager.find(started.id()).orElseThrow().state() == ServiceState.STOPPED);
            }
        }
    }
}
