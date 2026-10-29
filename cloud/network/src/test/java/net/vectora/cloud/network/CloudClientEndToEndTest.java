package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.vectora.cloud.client.CloudClient;
import net.vectora.cloud.client.CloudClientEvent;
import net.vectora.cloud.client.CloudClientListener;
import net.vectora.cloud.client.CloudClientSettings;
import net.vectora.cloud.client.NotConnectedException;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.GroupName;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.RegisterServer;
import net.vectora.protocol.message.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Vectora-CloudClient against the real server over loopback. */
class CloudClientEndToEndTest {

    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final ServiceId SURVIVAL = new ServiceId("survival-1");
    private static final ChannelName CHAT = new ChannelName("test.chat");

    private final ServiceTokens tokens = new ServiceTokens();
    private final AuthToken lobbyToken = tokens.issue(LOBBY);
    private final AuthToken survivalToken = tokens.issue(SURVIVAL);
    private final RecordingListener listener = new RecordingListener();
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).close();
        }
    }

    private CloudNetworkServer startServer(int port) throws IOException {
        CloudNetworkServer server = CloudNetworkServer.builder(
                NetworkSettings.defaults().withAddress("127.0.0.1", port), tokens).listener(listener).build();
        cleanup.add(server);
        server.start();
        return server;
    }

    private CloudClient client(InetSocketAddress address, ServiceId service, AuthToken token) {
        CloudClientSettings settings = CloudClientSettings.of("127.0.0.1", address.getPort(), service, token)
                .withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(5),
                        Duration.ofSeconds(2), Duration.ofMillis(50), Duration.ofMillis(200));
        CloudClient client = CloudClient.create(settings);
        cleanup.add(client);
        return client;
    }

    @Test
    void connectsReportsAndRepeatsReadinessAfterAReconnect() throws Exception {
        CloudNetworkServer server = startServer(0);
        CloudClient client = client(server.localAddress(), LOBBY, lobbyToken);
        Events events = new Events();
        client.addListener(events);
        // Ready before the connection exists: remembered and sent once connected.
        assertFalse(client.markReady());
        client.start();
        assertEquals("connected lobby-1", listener.next());
        assertEquals("ready lobby-1", listener.next());
        assertEquals("connected", events.next());
        assertTrue(client.reportStatus(5, 100, 19.5, 12.0));
        String status = listener.next();
        assertTrue(status.startsWith("status lobby-1 5 heapMax="), status);

        // The cloud drops the connection; the client comes back on its own and repeats its state.
        server.disconnect(LOBBY, Disconnect.Reason.NORMAL);
        assertEquals("disconnected lobby-1 NORMAL", listener.next());
        assertEquals("disconnected NORMAL", events.next());
        assertEquals("connected lobby-1", listener.next());
        assertEquals("ready lobby-1", listener.next());
        assertTrue(listener.next().startsWith("status lobby-1 5"));
        assertEquals("connected", events.next());
    }

    @Test
    void reconnectsWhenTheCloudStartsLaterOrRestarts() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        CloudClient client = client(new InetSocketAddress("127.0.0.1", port), LOBBY, lobbyToken);
        client.start();
        Thread.sleep(300); // a few refused attempts with backoff
        assertFalse(client.isConnected());

        CloudNetworkServer first = startServer(port);
        assertEquals("connected lobby-1", listener.next());
        awaitTrue(client::isConnected);

        first.close();
        assertEquals("disconnected lobby-1 SHUTDOWN", listener.next());
        awaitTrue(() -> !client.isConnected());

        startServer(port);
        assertEquals("connected lobby-1", listener.next());
        awaitTrue(client::isConnected);
    }

    @Test
    void wrongTokenIsReportedAndNeverConnects() throws Exception {
        CloudNetworkServer server = startServer(0);
        CloudClient client = client(server.localAddress(), LOBBY, survivalToken);
        Events events = new Events();
        client.addListener(events);
        client.start();
        assertEquals("rejected INVALID_TOKEN", events.next());
        assertEquals("rejected INVALID_TOKEN", events.next()); // it keeps trying, with backoff
        assertFalse(client.isConnected());
        assertNull(listener.poll(200));
    }

    @Test
    void messagingBetweenTwoClients() throws Exception {
        CloudNetworkServer server = startServer(0);
        CloudClient lobby = client(server.localAddress(), LOBBY, lobbyToken);
        CloudClient survival = client(server.localAddress(), SURVIVAL, survivalToken);
        BlockingQueue<String> atLobby = new LinkedBlockingQueue<>();
        BlockingQueue<String> atSurvival = new LinkedBlockingQueue<>();
        lobby.subscribe(CHAT, message -> atLobby.add(new String(message.payload(), java.nio.charset.StandardCharsets.UTF_8)));
        CloudClient.Subscription subscription = survival.subscribe(CHAT,
                message -> atSurvival.add(new String(message.payload(), java.nio.charset.StandardCharsets.UTF_8)));
        lobby.start();
        survival.start();
        awaitTrue(() -> server.isConnected(LOBBY) && server.isConnected(SURVIVAL));
        // The cloud registers a service before the service has read its AuthResult.
        awaitTrue(() -> lobby.isConnected() && survival.isConnected());

        assertTrue(lobby.publish(CHAT, "hi from lobby".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("hi from lobby", atSurvival.poll(5, TimeUnit.SECONDS));
        assertTrue(survival.publish(CHAT, "hi back".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("hi back", atLobby.poll(5, TimeUnit.SECONDS));
        // Nobody hears their own message.
        assertNull(atLobby.poll(200, TimeUnit.MILLISECONDS));
        assertNull(atSurvival.poll(10, TimeUnit.MILLISECONDS));

        subscription.close();
        lobby.publish(CHAT, "unheard".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(atSurvival.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void requestsInBothDirections() throws Exception {
        CloudNetworkServer server = startServer(0);
        server.handleRequests(new ChannelName("cloud.time"),
                (service, request) -> CompletableFuture.completedFuture(new Response(Response.Status.OK,
                        service.value().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        CloudClient client = client(server.localAddress(), LOBBY, lobbyToken);
        client.handleRequests(new ChannelName("service.players"),
                request -> CompletableFuture.completedFuture(new Response(Response.Status.OK, new byte[] {3})));

        CompletableFuture<Response> early = client.request(new ChannelName("cloud.time"), new byte[0]);
        assertInstanceOf(NotConnectedException.class,
                assertThrows(ExecutionException.class, early::get).getCause());

        client.start();
        awaitTrue(client::isConnected);
        Response fromCloud = client.request(new ChannelName("cloud.time"), new byte[0]).get(5, TimeUnit.SECONDS);
        assertEquals(Response.Status.OK, fromCloud.status());
        assertArrayEquals("lobby-1".getBytes(java.nio.charset.StandardCharsets.UTF_8), fromCloud.payload());
        Response missing = client.request(new ChannelName("cloud.nothing"), new byte[0]).get(5, TimeUnit.SECONDS);
        assertEquals(Response.Status.NO_HANDLER, missing.status());

        Response fromService = server.request(LOBBY, new net.vectora.protocol.message.Request(
                new ChannelName("service.players"), new byte[0])).get(5, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {3}, fromService.payload());
    }

    @Test
    void cloudMessagesReachTheListenerAndShutdownIsAnnounced() throws Exception {
        CloudNetworkServer server = startServer(0);
        CloudClient proxy = client(server.localAddress(), LOBBY, lobbyToken);
        Events events = new Events();
        proxy.addListener(events);
        proxy.start();
        assertEquals("connected", events.next());
        RegisterServer register = new RegisterServer(SURVIVAL, "127.0.0.1", 30001, new GroupName("survival"), false);
        assertTrue(server.send(LOBBY, register));
        assertEquals("message RegisterServer", events.next());

        assertEquals("connected lobby-1", listener.next());
        proxy.close();
        assertEquals("disconnected lobby-1 SHUTDOWN", listener.next());
        assertThrows(IllegalStateException.class, proxy::start);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(10);
        }
    }

    /** Client-side events as text. */
    private static final class Events implements CloudClientListener {

        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

        @Override
        public void onEvent(CloudClientEvent event) {
            queue.add(switch (event) {
                case CloudClientEvent.Connected connected -> "connected";
                case CloudClientEvent.Disconnected(Disconnect.Reason reason) -> "disconnected " + reason;
                case CloudClientEvent.Rejected(AuthResult.Outcome outcome) -> "rejected " + outcome;
                case CloudClientEvent.MessageReceived(Message message) ->
                        "message " + message.getClass().getSimpleName();
            });
        }

        String next() throws InterruptedException {
            String event = queue.poll(10, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("no client event within 10 s");
            }
            return event;
        }
    }
}
