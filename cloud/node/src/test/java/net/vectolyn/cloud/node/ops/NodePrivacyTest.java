// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import net.vectolyn.cloud.node.channel.NetworkPlayers;
import net.vectolyn.cloud.privacy.PrivacyConfig;
import net.vectolyn.cloud.privacy.PrivacyRequests;
import net.vectolyn.cloud.privacy.TrackingStore;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodePrivacyTest {

    private static final ServiceId PROXY = new ServiceId("proxy-1");
    private static final ServiceId OTHER_PROXY = new ServiceId("proxy-2");
    private static final ServiceId LOBBY = new ServiceId("lobby-1");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static String ask(NodePrivacy handler, ServiceId from, String payload) throws Exception {
        var response = handler.handle(from, new Request(NodePrivacy.CHANNEL, payload.getBytes(StandardCharsets.UTF_8)))
                .toCompletableFuture().get();
        return new String(response.payload(), StandardCharsets.UTF_8);
    }

    private static NodePrivacy handler(Path dir, TrackingStore[] storeOut) throws Exception {
        TrackingStore store = TrackingStore.open(dir.resolve("t.json"), 90, Clock.systemUTC());
        storeOut[0] = store;
        PrivacyRequests requests = new PrivacyRequests(store, new PrivacyConfig.Tracking(true, 90, "Example, a@example.org"));
        NetworkPlayers.Player player = new NetworkPlayers.Player(PLAYER, "Steve", PROXY, LOBBY, false);
        return new NodePrivacy(requests, id -> id.equals(PROXY) || id.equals(OTHER_PROXY),
                uuid -> uuid.equals(PLAYER) ? Optional.of(player) : Optional.empty(), id -> Optional.of("lobby"));
    }

    @Test
    void answersOnlyTheProxyTheAskingPlayerIsOnline(@TempDir Path dir) throws Exception {
        TrackingStore[] store = new TrackingStore[1];
        NodePrivacy handler = handler(dir, store);
        assertEquals("ok", ask(handler, PROXY, PLAYER + " grant"));
        assertTrue(store[0].consented(PLAYER));
        assertEquals(1, store[0].find(PLAYER).orElseThrow().logins());
        // another proxy, a backend server and an unknown player cannot touch the record
        assertEquals("error not-online", ask(handler, OTHER_PROXY, PLAYER + " revoke"));
        assertEquals("error not-online", ask(handler, LOBBY, PLAYER + " data"));
        assertEquals("error not-online", ask(handler, PROXY, UUID.randomUUID() + " revoke"));
        assertTrue(store[0].consented(PLAYER));
        assertEquals("ok", ask(handler, PROXY, PLAYER + " revoke"));
        assertFalse(store[0].consented(PLAYER));
    }

    @Test
    void malformedRequestsAreRejected(@TempDir Path dir) throws Exception {
        NodePrivacy handler = handler(dir, new TrackingStore[1]);
        assertEquals("error bad-request", ask(handler, PROXY, "grant"));
        assertEquals("error bad-request", ask(handler, PROXY, "not-a-uuid-not-a-uuid-not-a-uuid-xx grant"));
        assertEquals("error bad-request", ask(handler, PROXY, PLAYER + " "));
    }
}
