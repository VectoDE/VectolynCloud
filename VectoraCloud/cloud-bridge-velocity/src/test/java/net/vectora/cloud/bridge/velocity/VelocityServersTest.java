package net.vectora.cloud.bridge.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.vectora.cloud.bridge.ProxyRole;
import net.vectora.cloud.bridge.ServerRegistry;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The adapter against a scripted {@code ProxyServer}; loads velocity-api 4.3 classes. */
@Tag("requires-java25")
class VelocityServersTest {

    private final List<String> calls = new ArrayList<>();
    private final Set<String> runtime = new HashSet<>();
    private boolean lobbyWithdrawn;

    // No field, parameter or return type of a velocity-api class: JUnit reads the declared methods
    // of every test class during discovery, also on Java 21, where those classes cannot load.

    private Object scriptedProxy() {
        ClassLoader loader = getClass().getClassLoader();
        Object lobby = Proxy.newProxyInstance(loader, new Class<?>[] {RegisteredServer.class}, (self, method, args) -> {
            if (method.getName().equals("getServerInfo")) {
                return new ServerInfo("lobby", java.net.InetSocketAddress.createUnresolved("127.0.0.1", 30001));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object server = Proxy.newProxyInstance(loader, new Class<?>[] {RegisteredServer.class}, (self, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });
        Object config = Proxy.newProxyInstance(loader, new Class<?>[] {ProxyConfig.class}, (self, method, args) -> {
            if (method.getName().equals("getServers")) {
                return Map.of("lobby", "127.0.0.1:30001");
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return Proxy.newProxyInstance(loader, new Class<?>[] {ProxyServer.class}, (self, method, args) -> switch (method.getName()) {
            case "getConfiguration" -> config;
            case "getServer" -> "lobby".equals(args[0]) && !lobbyWithdrawn ? Optional.of(lobby)
                    : runtime.contains((String) args[0]) ? Optional.of(server) : Optional.empty();
            case "registerServer" -> {
                ServerInfo info = (ServerInfo) args[0];
                calls.add("+" + info.getName() + "@" + info.getAddress().getHostString() + ":"
                        + info.getAddress().getPort());
                runtime.add(info.getName());
                yield server;
            }
            case "unregisterServer" -> {
                ServerInfo info = (ServerInfo) args[0];
                calls.add("-" + info.getName());
                if (!runtime.remove(info.getName()) && "lobby".equals(info.getName())) {
                    lobbyWithdrawn = true;
                }
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void registersAndRemovesThroughTheProxyApi() {
        VelocityServers servers = new VelocityServers((ProxyServer) scriptedProxy());
        ServerRegistry registry = new ServerRegistry(servers, ProxyRole.MAIN);
        assertTrue(servers.isConfigured("lobby"));
        assertFalse(servers.isConfigured("bedwars-1"));

        assertEquals(ServerRegistry.Outcome.REGISTERED, registry.register(new RegisterServer(
                new ServiceId("bedwars-1"), "127.0.0.1", 30002, new GroupName("bedwars"), false)));
        assertTrue(servers.exists("bedwars-1"));
        assertEquals(ServerRegistry.Outcome.CONFIGURED, registry.register(new RegisterServer(
                new ServiceId("lobby"), "127.0.0.1", 30009, new GroupName("lobby"), false)));
        assertEquals(ServerRegistry.Outcome.UNREGISTERED,
                registry.unregister(new UnregisterServer(new ServiceId("bedwars-1"))));
        servers.unregister("bedwars-1"); // second removal does nothing

        assertEquals(List.of("+bedwars-1@127.0.0.1:30002", "-bedwars-1"), calls);
        assertFalse(servers.exists("bedwars-1"));
    }

    @Test
    void withdrawsAndRestoresAVelocityTomlServer() {
        VelocityServers servers = new VelocityServers((ProxyServer) scriptedProxy());
        ServerRegistry registry = new ServerRegistry(servers, ProxyRole.MAIN);

        assertEquals(ServerRegistry.Outcome.WITHDRAWN, registry.unregister(new UnregisterServer(new ServiceId("lobby"))));
        assertFalse(servers.exists("lobby"));
        assertEquals(ServerRegistry.Outcome.REGISTERED, registry.register(new RegisterServer(
                new ServiceId("lobby"), "127.0.0.1", 30001, new GroupName("lobby"), false)));
        assertTrue(servers.exists("lobby"));
        assertEquals(List.of("-lobby", "+lobby@127.0.0.1:30001"), calls);
    }
}
