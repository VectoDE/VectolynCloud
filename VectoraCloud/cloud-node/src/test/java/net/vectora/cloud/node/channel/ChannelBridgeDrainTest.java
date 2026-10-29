package net.vectora.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.vectora.cloud.config.model.TaskMode;
import net.vectora.cloud.network.ServiceEvent;
import net.vectora.cloud.node.service.ServiceInfo;
import net.vectora.cloud.node.service.ServiceState;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.PlayerJoin;
import net.vectora.protocol.message.PlayerSwitch;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.ServerCapacity;
import net.vectora.protocol.message.ServiceStatus;
import net.vectora.protocol.message.TransferPlayer;
import org.junit.jupiter.api.Test;

/** Emptying a backend before it stops, on a fake service core, channel and clock. */
class ChannelBridgeDrainTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId ONE = new ServiceId("minigame-1");
    private static final ServiceId TWO = new ServiceId("minigame-2");
    private static final UUID ALEX = new UUID(0, 1);
    private static final UUID STEVE = new UUID(0, 2);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void aPlayerWhoArrivesDuringThePollGetsATransferOfHisOwn() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        f.ticker.onSleep = poll -> {
            if (poll == 1) {
                // Alex has moved; Steve was routed to minigame-1 while it was being closed.
                f.move(ALEX, ONE, TWO);
                f.join(STEVE, ONE);
                f.status(ONE, 1);
            } else if (poll == 2) {
                f.move(STEVE, ONE, TWO);
                f.status(ONE, 0);
            }
        };
        assertTrue(f.bridge.drain(ONE, TIMEOUT));
        assertEquals(List.of(new TransferPlayer(ALEX, TWO, TransferPlayer.Reason.SCALE_DOWN),
                new TransferPlayer(STEVE, TWO, TransferPlayer.Reason.SCALE_DOWN)), f.channel.transfers());
        assertEquals(List.of(false), f.channel.capacities(ONE), "closed, and it stays closed until it stops");
    }

    @Test
    void playersLeftAfterTheTimeoutOpenTheBackendAgain() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        assertFalse(f.bridge.drain(ONE, Duration.ofSeconds(1)));
        assertEquals(List.of(false, true), f.channel.capacities(ONE));
        assertEquals(Duration.ofSeconds(1), f.ticker.slept(), "it waited the whole timeout");
    }

    @Test
    void withoutAnotherBackendTheDrainGivesUpAtOnceAndOpensTheBackendAgain() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backendOfGroup(TWO, "lobby");
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        assertFalse(f.bridge.drain(ONE, TIMEOUT));
        assertEquals(Duration.ZERO, f.ticker.slept(), "no wait for moves that are never sent");
        assertEquals(List.of(), f.channel.transfers(), "a backend of another group is no target");
        assertEquals(List.of(false, true), f.channel.capacities(ONE));
    }

    @Test
    void anInterruptedDrainOpensTheBackendAgain() {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        f.ticker.interrupt = true;
        assertThrows(InterruptedException.class, () -> f.bridge.drain(ONE, TIMEOUT));
        assertEquals(List.of(false, true), f.channel.capacities(ONE));
    }

    @Test
    void emptyMeansNobodyForTheProxiesAndNoneInTheBackendsOwnReport() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        assertFalse(f.bridge.drain(ONE, Duration.ofSeconds(1)), "no report of its own yet");

        f.status(ONE, 1);
        assertFalse(f.bridge.drain(ONE, Duration.ofSeconds(1)), "a player the proxies do not list yet");

        // Counter-check: both lists empty, at once and without a wait.
        f.status(ONE, 0);
        Duration before = f.ticker.slept();
        assertTrue(f.bridge.drain(ONE, Duration.ofSeconds(1)));
        assertEquals(before, f.ticker.slept());
    }

    // ---------------------------------------------------------------- fixture

    private static final class Fixture {
        final FakeServices services = new FakeServices();
        final FakeChannel channel = new FakeChannel();
        final FakeTicker ticker = new FakeTicker();
        final ChannelBridge bridge = new ChannelBridge(services, ticker);

        Fixture() {
            bridge.attach(channel);
        }

        Fixture backend(ServiceId id) {
            return backendOfGroup(id, "minigame");
        }

        Fixture backendOfGroup(ServiceId id, String group) {
            RegisterServer message = new RegisterServer(id, "127.0.0.1", 30000 + services.backends.size(),
                    new GroupName(group), false);
            services.backends.put(id, message);
            // As the service core does it once the backend is RUNNING.
            bridge.stateChanged(new ServiceInfo(id, group, group, 1, TaskMode.DYNAMIC, ServiceState.RUNNING,
                    message.port(), Path.of("x"), OptionalLong.of(1), Optional.empty(), OptionalInt.empty(), 0),
                    ServiceState.STARTING);
            channel.sent.clear();
            return this;
        }

        void join(UUID player, ServiceId backend) {
            bridge.onEvent(new ServiceEvent.MessageReceived(PROXY, new PlayerJoin(player, "Player", backend, false)));
        }

        void move(UUID player, ServiceId from, ServiceId to) {
            bridge.onEvent(new ServiceEvent.MessageReceived(PROXY,
                    new PlayerSwitch(player, "Player", from, to, false)));
        }

        void status(ServiceId backend, int players) {
            bridge.onEvent(new ServiceEvent.StatusReported(new ServiceStatus(backend, players, 50, 20, 5, 0,
                    ServiceStatus.UNKNOWN_HEAP_MAX)));
        }
    }

    private static final class FakeServices implements ChannelBridge.Services {
        final Map<ServiceId, RegisterServer> backends = new LinkedHashMap<>();

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
            return proxy.equals(PROXY) && backends.containsKey(name) ? Optional.of(name) : Optional.empty();
        }

        @Override
        public Optional<RegisterServer> registration(ServiceId backend) {
            return Optional.ofNullable(backends.get(backend));
        }

        @Override
        public Map<ServiceId, RegisterServer> registrations(boolean legacy) {
            return Map.copyOf(backends);
        }

        @Override
        public List<ServiceId> runningProxies(boolean legacy) {
            return List.of(PROXY);
        }

        @Override
        public OptionalInt slots(ServiceId backend) {
            return backends.containsKey(backend) ? OptionalInt.of(50) : OptionalInt.empty();
        }

        @Override
        public boolean hasProcess(ServiceId id) {
            return backends.containsKey(id);
        }
    }

    /** The proxy is always connected; every message is recorded. */
    private static final class FakeChannel implements ChannelBridge.Channel {
        final List<Message> sent = new ArrayList<>();

        @Override
        public boolean isConnected(ServiceId service) {
            return service.equals(PROXY);
        }

        @Override
        public void send(ServiceId service, Message message) {
            sent.add(message);
        }

        @Override
        public void disconnect(ServiceId service, Disconnect.Reason reason) {
            // Not needed.
        }

        List<TransferPlayer> transfers() {
            return sent.stream().filter(TransferPlayer.class::isInstance).map(TransferPlayer.class::cast).toList();
        }

        /** The {@code open} flags of the capacities sent for a backend, in order. */
        List<Boolean> capacities(ServiceId backend) {
            return sent.stream().filter(ServerCapacity.class::isInstance).map(ServerCapacity.class::cast)
                    .filter(c -> c.name().equals(backend)).map(ServerCapacity::open).toList();
        }
    }

    /** Time passes only in {@link #sleep}; a hook sees each poll, numbered from 1. */
    private static final class FakeTicker implements ChannelBridge.Ticker {
        long nanos;
        int polls;
        boolean interrupt;
        IntConsumer onSleep = poll -> { };

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (interrupt) {
                throw new InterruptedException("test");
            }
            nanos += duration.toNanos();
            onSleep.accept(++polls);
        }

        Duration slept() {
            return Duration.ofNanos(nanos);
        }
    }
}
