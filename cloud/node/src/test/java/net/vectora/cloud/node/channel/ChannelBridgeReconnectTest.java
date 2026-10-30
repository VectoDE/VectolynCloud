package net.vectora.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.network.ServiceEvent;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.UnregisterServer;
import org.junit.jupiter.api.Test;

/**
 * The server lists of the proxies across a reconnect of the proxy's bridge, through the whole
 * {@link ChannelBridge} on a fake service core and channel: a backend that ended while the proxy
 * was away is withdrawn when it connects again, and only that one.
 */
class ChannelBridgeReconnectTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId ONE = new ServiceId("minigame-1");
    private static final ServiceId TWO = new ServiceId("minigame-2");
    private static final ServiceId THREE = new ServiceId("minigame-3");

    private final FakeServices services = new FakeServices();
    private final FakeChannel channel = new FakeChannel();
    private final ChannelBridge bridge = new ChannelBridge(services, ChannelBridge.Ticker.SYSTEM);

    ChannelBridgeReconnectTest() {
        bridge.attach(channel);
    }

    private void running(ServiceId backend) {
        RegisterServer message = new RegisterServer(backend, "127.0.0.1", 30000 + services.backends.size(),
                new GroupName("minigame"), false);
        services.backends.put(backend, message);
        bridge.stateChanged(info(backend, ServiceState.RUNNING), ServiceState.STARTING);
    }

    private void stopped(ServiceId backend) {
        services.backends.remove(backend);
        bridge.stateChanged(info(backend, ServiceState.STOPPED), ServiceState.STOPPING);
    }

    private static ServiceInfo info(ServiceId id, ServiceState state) {
        return new ServiceInfo(id, "minigame", "minigame", 1, TaskMode.DYNAMIC, state, 30000, Path.of("x"),
                OptionalLong.of(1), Optional.empty(), OptionalInt.empty(), 0);
    }

    private void reconnect() {
        channel.connected = true;
        bridge.onEvent(new ServiceEvent.Connected(PROXY, new InetSocketAddress("127.0.0.1", 40000)));
    }

    private List<ServiceId> withdrawn() {
        return channel.sent.stream().filter(UnregisterServer.class::isInstance).map(UnregisterServer.class::cast)
                .map(UnregisterServer::name).toList();
    }

    private List<ServiceId> registered() {
        return channel.sent.stream().filter(RegisterServer.class::isInstance).map(RegisterServer.class::cast)
                .map(RegisterServer::name).toList();
    }

    @Test
    void aBackendThatEndedWhileTheProxyWasAwayIsWithdrawnOnTheReconnect() {
        running(ONE);
        running(TWO);
        running(THREE);
        assertEquals(List.of(ONE, TWO, THREE), registered(), "sent while the proxy was connected");

        // The proxy's bridge loses its connection; the proxy keeps its list. Two backends end in the
        // gap: their UnregisterServer cannot reach it.
        channel.connected = false;
        channel.sent.clear();
        stopped(TWO);
        stopped(THREE);
        assertEquals(List.of(), channel.sent);

        reconnect();
        assertEquals(List.of(TWO, THREE), withdrawn(), "exactly the backends that ended, sorted");
        assertEquals(List.of(ONE), registered(), "the running one is sent again");
        int unregister = channel.sent.indexOf(new UnregisterServer(TWO));
        int register = channel.sent.indexOf(services.backends.get(ONE));
        assertTrue(unregister >= 0 && unregister < register, "withdrawn before the list is sent: " + channel.sent);

        // A second reconnect has nothing left to withdraw.
        channel.connected = false;
        channel.sent.clear();
        reconnect();
        assertEquals(List.of(), withdrawn());
        assertEquals(List.of(ONE), registered());
    }

    @Test
    void aBackendWithdrawnWhileTheProxyWasConnectedIsNotWithdrawnAgain() {
        running(ONE);
        running(TWO);
        stopped(TWO);
        assertEquals(List.of(TWO), withdrawn(), "reached the proxy at once");

        channel.connected = false;
        channel.sent.clear();
        reconnect();
        assertEquals(List.of(), withdrawn(), "nothing stale after a clean withdrawal");
        assertEquals(List.of(ONE), registered());
    }

    @Test
    void aProxyThatEndedStartsWithAnEmptyList() {
        running(ONE);
        running(TWO);
        // The proxy process ends; its next start lists nothing from before.
        channel.connected = false;
        services.proxyProcess = false;
        bridge.stateChanged(new ServiceInfo(PROXY, "proxy", "proxy", 1, TaskMode.STATIC, ServiceState.STOPPED, 25565,
                Path.of("x"), OptionalLong.of(2), Optional.empty(), OptionalInt.empty(), 0), ServiceState.STOPPING);
        stopped(TWO);
        services.proxyProcess = true;
        channel.sent.clear();
        reconnect();
        assertEquals(List.of(), withdrawn(), "a new proxy process never had minigame-2");
        assertEquals(List.of(ONE), registered());
    }

    private static final class FakeServices implements ChannelBridge.Services {
        final Map<ServiceId, RegisterServer> backends = new LinkedHashMap<>();
        boolean proxyProcess = true;

        @Override
        public boolean markReady(ServiceId id) {
            return false;
        }

        @Override
        public Optional<Boolean> proxyKind(ServiceId id) {
            return id.equals(PROXY) ? Optional.of(false) : Optional.empty();
        }

        @Override
        public Optional<Boolean> backendKind(ServiceId backend) {
            return backends.containsKey(backend) ? Optional.of(false) : Optional.empty();
        }

        @Override
        public Optional<ServiceId> backendNamed(ServiceId proxy, ServiceId name) {
            return backends.containsKey(name) ? Optional.of(name) : Optional.empty();
        }

        @Override
        public Optional<RegisterServer> registration(ServiceId backend) {
            return Optional.ofNullable(backends.get(backend));
        }

        @Override
        public Map<ServiceId, RegisterServer> registrations(boolean legacy) {
            return new LinkedHashMap<>(backends);
        }

        @Override
        public List<ServiceId> runningProxies(boolean legacy) {
            return List.of(PROXY);
        }

        @Override
        public OptionalInt slots(ServiceId backend) {
            return OptionalInt.empty();
        }

        @Override
        public boolean hasProcess(ServiceId id) {
            return id.equals(PROXY) ? proxyProcess : backends.containsKey(id);
        }
    }

    /** One proxy whose connection the test switches; what it is sent while connected is recorded. */
    private static final class FakeChannel implements ChannelBridge.Channel {
        final List<Message> sent = new ArrayList<>();
        boolean connected = true;

        @Override
        public boolean isConnected(ServiceId service) {
            return connected && service.equals(PROXY);
        }

        @Override
        public void send(ServiceId service, Message message) {
            sent.add(message);
        }

        @Override
        public void disconnect(ServiceId service, Disconnect.Reason reason) {
            // Not needed.
        }
    }
}