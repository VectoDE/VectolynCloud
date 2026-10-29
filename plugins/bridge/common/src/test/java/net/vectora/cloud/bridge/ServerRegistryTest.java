package net.vectora.cloud.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;
import org.junit.jupiter.api.Test;

class ServerRegistryTest {

    /** A proxy server list in memory: configured servers plus runtime ones. */
    static final class FakeServers implements ProxyServers {
        final Set<String> configured;
        final Set<String> withdrawn = new HashSet<>();
        final Map<String, String> runtime = new HashMap<>();
        final List<String> log = new ArrayList<>();

        FakeServers(Set<String> configured) {
            this.configured = configured;
        }

        @Override
        public boolean isConfigured(String name) {
            return configured.contains(name);
        }

        @Override
        public boolean exists(String name) {
            return (configured.contains(name) && !withdrawn.contains(name)) || runtime.containsKey(name);
        }

        @Override
        public void withdraw(String name) {
            withdrawn.add(name);
            log.add("~" + name);
        }

        @Override
        public void register(String name, String host, int port) {
            runtime.put(name, host + ":" + port);
            log.add("+" + name + "@" + host + ":" + port);
        }

        @Override
        public void unregister(String name) {
            runtime.remove(name);
            log.add("-" + name);
        }

        final Map<String, Integer> players = new HashMap<>();

        @Override
        public int playersOn(String name) {
            return players.getOrDefault(name, 0);
        }
    }

    private final FakeServers servers = new FakeServers(Set.of("lobby"));
    private final ServerRegistry main = new ServerRegistry(servers, ProxyRole.MAIN);

    @Test
    void registersReplacesAndUnregistersItsOwnServers() {
        assertEquals(ServerRegistry.Outcome.REGISTERED, main.register(server("bedwars-1", 30002, false)));
        assertEquals(ServerRegistry.Outcome.UNCHANGED, main.register(server("bedwars-1", 30002, false)));
        assertEquals(ServerRegistry.Outcome.REPLACED, main.register(server("bedwars-1", 30005, false)));
        assertEquals(Set.of("bedwars-1"), main.registered());
        assertEquals(ServerRegistry.Outcome.UNREGISTERED, main.unregister(new UnregisterServer(new ServiceId("bedwars-1"))));
        assertEquals(List.of("+bedwars-1@127.0.0.1:30002", "-bedwars-1", "+bedwars-1@127.0.0.1:30005", "-bedwars-1"),
                servers.log);
        assertTrue(main.registered().isEmpty());
    }

    @Test
    void theMainProxyRefusesLegacyServersAndTheLegacyProxyCurrentOnes() {
        assertEquals(ServerRegistry.Outcome.REFUSED_ROLE, main.register(server("pvp-1", 30010, true)));
        ServerRegistry legacy = new ServerRegistry(servers, ProxyRole.LEGACY);
        assertEquals(ServerRegistry.Outcome.REFUSED_ROLE, legacy.register(server("bedwars-1", 30002, false)));
        // Counter-check: each takes its own kind.
        assertEquals(ServerRegistry.Outcome.REGISTERED, legacy.register(server("pvp-1", 30010, true)));
        assertEquals(List.of("+pvp-1@127.0.0.1:30010"), servers.log);
    }

    @Test
    void configuredServersAreNeverReplacedAndForeignOnesAreLeftAlone() {
        assertEquals(ServerRegistry.Outcome.CONFIGURED, main.register(server("lobby", 30001, false)));
        assertEquals(ServerRegistry.Outcome.CONFIGURED, main.register(server("lobby", 30009, false)),
                "another address does not replace a velocity.toml server");
        servers.runtime.put("other-1", "10.0.0.5:25565");
        assertEquals(ServerRegistry.Outcome.REFUSED_FOREIGN, main.register(server("other-1", 30003, false)));
        assertEquals(ServerRegistry.Outcome.NOT_OWNED, main.unregister(new UnregisterServer(new ServiceId("other-1"))));
        assertTrue(servers.log.isEmpty());
        assertTrue(ServerRegistry.Outcome.REFUSED_FOREIGN.refused());
        assertFalse(ServerRegistry.Outcome.CONFIGURED.refused(), "a static backend sent again is no warning");
        assertFalse(ServerRegistry.Outcome.REGISTERED.refused());
    }

    @Test
    void aConfiguredServerTheCloudStopsIsWithdrawnAndComesBackWhenItRunsAgain() {
        // lobby-1 stopped, for example because servers.lobby was switched off on a reload.
        assertEquals(ServerRegistry.Outcome.WITHDRAWN, main.unregister(new UnregisterServer(new ServiceId("lobby"))));
        assertFalse(servers.exists("lobby"), "the proxy no longer lists a server the cloud stopped");
        assertEquals(ServerRegistry.Outcome.NOT_OWNED, main.unregister(new UnregisterServer(new ServiceId("lobby"))),
                "a second removal changes nothing");
        // Switched on again: back under the same name, now owned by the registry.
        assertEquals(ServerRegistry.Outcome.REGISTERED, main.register(server("lobby", 30001, false)));
        assertTrue(servers.exists("lobby"));
        assertEquals(ServerRegistry.Outcome.UNREGISTERED, main.unregister(new UnregisterServer(new ServiceId("lobby"))));
        assertEquals(ServerRegistry.Outcome.REGISTERED, main.register(server("lobby", 30004, false)));
        assertEquals(List.of("~lobby", "+lobby@127.0.0.1:30001", "-lobby", "+lobby@127.0.0.1:30004"), servers.log);
    }

    @Test
    void aConfiguredServerSomeoneElseTookOutIsNotPutBack() {
        servers.withdrawn.add("lobby");
        assertEquals(ServerRegistry.Outcome.REFUSED_FOREIGN, main.register(server("lobby", 30001, false)));
        assertEquals(ServerRegistry.Outcome.NOT_OWNED, main.unregister(new UnregisterServer(new ServiceId("lobby"))));
        assertTrue(servers.log.isEmpty());
    }

    @Test
    void roleFromThePlatformVariable() {
        assertEquals(ProxyRole.LEGACY, ProxyRole.fromPlatform("VELOCITY_LEGACY"));
        assertEquals(ProxyRole.LEGACY, ProxyRole.fromPlatform(" velocity_legacy "));
        assertEquals(ProxyRole.MAIN, ProxyRole.fromPlatform("VELOCITY"));
        assertEquals(ProxyRole.MAIN, ProxyRole.fromPlatform(null), "missing means the stricter main proxy");
        assertEquals(ProxyRole.MAIN, ProxyRole.fromPlatform("PAPER"));
    }

    @Test
    void theBridgeSaysWhichRoleItTookAndWarnsAboutAnUnknownPlatform() {
        assertTrue(ProxyRole.isProxyPlatform("VELOCITY"));
        assertTrue(ProxyRole.isProxyPlatform(" velocity_legacy "));
        assertFalse(ProxyRole.isProxyPlatform(null));
        assertFalse(ProxyRole.isProxyPlatform("PAPER"));
        assertFalse(ProxyRole.isProxyPlatform("VELOCITY-LEGACY"));

        String legacy = ProxyRole.describe("VELOCITY_LEGACY");
        assertTrue(legacy.contains("legacy proxy"), legacy);
        assertFalse(legacy.contains("expected"), "a known value gets no warning: " + legacy);
        assertTrue(ProxyRole.describe("VELOCITY").contains("main proxy"));
        String typo = ProxyRole.describe("VELOCITY-LEGACY-secretish");
        assertTrue(typo.contains("main proxy") && typo.contains("unknown value"), typo);
        assertFalse(typo.contains("secretish"), "the raw value is not logged: " + typo);
        assertTrue(ProxyRole.describe(null).contains("is not set"));
        assertTrue(ProxyRole.describe("  ").contains("is not set"));
    }

    static RegisterServer server(String name, int port, boolean legacy) {
        String group = name.substring(0, name.indexOf('-') < 0 ? name.length() : name.indexOf('-'));
        return new RegisterServer(new ServiceId(name), "127.0.0.1", port, new GroupName(group), legacy);
    }
}
