package net.vectolyn.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import net.vectolyn.cloud.config.model.TaskMode;
import net.vectolyn.cloud.network.ServiceEvent;
import net.vectolyn.cloud.node.channel.ChannelBridge.DrainResult;
import net.vectolyn.cloud.node.service.ServiceInfo;
import net.vectolyn.cloud.node.service.ServiceState;
import net.vectolyn.protocol.GroupName;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Message;
import net.vectolyn.protocol.message.NetworkKick;
import net.vectolyn.protocol.message.PlayerJoin;
import net.vectolyn.protocol.message.PlayerSwitch;
import net.vectolyn.protocol.message.RegisterServer;
import net.vectolyn.protocol.message.ServerCapacity;
import net.vectolyn.protocol.message.ServiceStatus;
import net.vectolyn.protocol.message.TransferPlayer;
import org.junit.jupiter.api.Test;

/** Emptying a backend before it stops, on a fake service core, channel and clock. */
class ChannelBridgeDrainTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId ONE = new ServiceId("minigame-1");
    private static final ServiceId TWO = new ServiceId("minigame-2");
    private static final ServiceId THREE = new ServiceId("minigame-3");
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
        assertEquals(DrainResult.EMPTY, f.bridge.drain(ONE, TIMEOUT));
        assertEquals(List.of(new TransferPlayer(ALEX, TWO, TransferPlayer.Reason.SCALE_DOWN),
                new TransferPlayer(STEVE, TWO, TransferPlayer.Reason.SCALE_DOWN)), f.channel.transfers());
        assertEquals(List.of(false), f.channel.capacities(ONE), "closed, and it stays closed until it stops");
    }

    @Test
    void aMoveThatDidNotHappenIsSentAgainWithAWaitThatDoubles() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.join(STEVE, ONE);
        f.status(ONE, 2);
        f.ticker.onSleep = poll -> {
            if (f.ticker.slept().equals(Duration.ofSeconds(1))) {
                // Steve's move worked; Alex's proxy never carried his out.
                f.move(STEVE, ONE, TWO);
                f.status(ONE, 1);
            }
        };
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(20)));
        // Waits of 2, 4, 8 and 8 seconds: sent at 0, 2, 6 and 14 s; the next would be at 22 s.
        assertEquals(List.of(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(6), Duration.ofSeconds(14)),
                f.channel.sentAt(ALEX));
        assertEquals(List.of(Duration.ZERO), f.channel.sentAt(STEVE), "Steve has moved: no second message");
        assertEquals(ChannelBridge.RETRY_FIRST, Duration.ofSeconds(2));
        assertEquals(ChannelBridge.RETRY_MAX, Duration.ofSeconds(8));
    }

    @Test
    void aMoveSentAgainKeepsItsTargetWhileThatOneIsOpenWithRoom() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO).backend(THREE);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        f.ticker.onSleep = poll -> {
            if (poll == 1) {
                // minigame-2 fills up a little; minigame-3 would now be the emptiest.
                f.join(STEVE, TWO);
                f.join(new UUID(0, 3), TWO);
            }
        };
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(3)));
        assertEquals(List.of(new TransferPlayer(ALEX, TWO, TransferPlayer.Reason.SCALE_DOWN),
                new TransferPlayer(ALEX, TWO, TransferPlayer.Reason.SCALE_DOWN)), f.channel.transfers(),
                "no second hop to minigame-3");
    }

    @Test
    void aMoveSentAgainGoesElsewhereOnceItsTargetIsGone() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO).backend(THREE);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        f.ticker.onSleep = poll -> {
            if (poll == 1) {
                f.services.backends.remove(TWO);
            }
        };
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(3)));
        assertEquals(List.of(new TransferPlayer(ALEX, TWO, TransferPlayer.Reason.SCALE_DOWN),
                new TransferPlayer(ALEX, THREE, TransferPlayer.Reason.SCALE_DOWN)), f.channel.transfers());
    }

    @Test
    void aPlayerWhoComesBackIsMovedAgainAtOnce() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        f.ticker.onSleep = poll -> {
            if (poll == 1) {
                // Moved, but the backend's own report still counts him.
                f.move(ALEX, ONE, TWO);
            } else if (poll == 2) {
                f.move(ALEX, TWO, ONE);
            } else if (poll == 3) {
                f.move(ALEX, ONE, TWO);
                f.status(ONE, 0);
            }
        };
        // Back on it at 0.5 s: a new move at once, not after the wait of his first one.
        assertEquals(DrainResult.EMPTY, f.bridge.drain(ONE, TIMEOUT));
        assertEquals(List.of(Duration.ZERO, Duration.ofMillis(500)), f.channel.sentAt(ALEX));
    }

    @Test
    void playersLeftAfterTheTimeoutOpenTheBackendAgain() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backend(TWO);
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(1)));
        assertEquals(List.of(false, true), f.channel.capacities(ONE));
        assertEquals(Duration.ofSeconds(1), f.ticker.slept(), "it waited the whole timeout");
    }

    @Test
    void withoutAnotherBackendTheDrainGivesUpAtOnceAndOpensTheBackendAgain() throws InterruptedException {
        Fixture f = new Fixture().backend(ONE).backendOfGroup(TWO, "lobby");
        f.join(ALEX, ONE);
        f.status(ONE, 1);
        assertEquals(DrainResult.NO_TARGET, f.bridge.drain(ONE, TIMEOUT));
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
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(1)), "no report of its own yet");

        f.status(ONE, 1);
        assertEquals(DrainResult.TIMEOUT, f.bridge.drain(ONE, Duration.ofSeconds(1)),
                "a player the proxies do not list yet");

        // Counter-check: both lists empty, at once and without a wait.
        f.status(ONE, 0);
        Duration before = f.ticker.slept();
        assertEquals(DrainResult.EMPTY, f.bridge.drain(ONE, Duration.ofSeconds(1)));
        assertEquals(before, f.ticker.slept());
    }

    @Test
    void aBackendThatIsNotListedIsOnlyChecked() throws InterruptedException {
        Fixture f = new Fixture();
        f.status(ONE, 0);
        assertEquals(DrainResult.EMPTY, f.bridge.drain(ONE, TIMEOUT));
        f.status(ONE, 1);
        assertEquals(DrainResult.NO_TARGET, f.bridge.drain(ONE, TIMEOUT), "nobody can move its players");
        assertEquals(List.of(), f.channel.sent);
        assertEquals(Duration.ZERO, f.ticker.slept());
    }

    /**
     * A network kick goes on to the proxies only from a backend on their lists; the proxy itself,
     * a backend of another service id or an unknown service cannot trigger one.
     */
    @Test
    void networkKicksArePassedOnOnlyFromListedBackends() {
        Fixture f = new Fixture().backend(ONE);
        NetworkKick kick = new NetworkKick(ALEX, ONE, "Spam");
        f.bridge.onEvent(new ServiceEvent.MessageReceived(PROXY, new NetworkKick(ALEX, PROXY, "Spam")));
        f.bridge.onEvent(new ServiceEvent.MessageReceived(TWO, new NetworkKick(ALEX, TWO, "Spam")));
        f.bridge.onEvent(new ServiceEvent.MessageReceived(TWO, kick));
        assertEquals(List.of(), f.channel.sent, "from a proxy, an unknown service or for another backend");
        // Counter-check: the listed backend's own kick reaches the proxy.
        f.bridge.onEvent(new ServiceEvent.MessageReceived(ONE, kick));
        assertEquals(List.of(kick), f.channel.sent);
    }

    // ---------------------------------------------------------------- fixture

    private static final class Fixture {
        final FakeServices services = new FakeServices();
        final FakeTicker ticker = new FakeTicker();
        final FakeChannel channel = new FakeChannel(ticker);
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
            channel.times.clear();
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

    /** The proxy is always connected; every message is recorded with the time it was sent. */
    private static final class FakeChannel implements ChannelBridge.Channel {
        final List<Message> sent = new ArrayList<>();
        final List<Duration> times = new ArrayList<>();
        final FakeTicker ticker;

        FakeChannel(FakeTicker ticker) {
            this.ticker = ticker;
        }

        @Override
        public boolean isConnected(ServiceId service) {
            return service.equals(PROXY);
        }

        @Override
        public void send(ServiceId service, Message message) {
            sent.add(message);
            times.add(ticker.slept());
        }

        /** When a move was sent for this player. */
        List<Duration> sentAt(UUID player) {
            List<Duration> result = new ArrayList<>();
            for (int i = 0; i < sent.size(); i++) {
                if (sent.get(i) instanceof TransferPlayer move && move.uuid().equals(player)) {
                    result.add(times.get(i));
                }
            }
            return result;
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
