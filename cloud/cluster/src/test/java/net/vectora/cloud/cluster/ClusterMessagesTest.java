package net.vectora.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.vectora.cloud.cluster.ClusterMessages.HelloOutcome;
import net.vectora.cloud.cluster.ClusterMessages.JoinOutcome;
import net.vectora.cloud.cluster.ClusterMessages.JoinReply;
import net.vectora.cloud.cluster.ClusterMessages.JoinRequest;
import net.vectora.cloud.cluster.ClusterMessages.NodeHello;
import net.vectora.cloud.cluster.ClusterMessages.NodeWelcome;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

class ClusterMessagesTest {

    private static final String SECRET = "0123456789abcdefghijklmnopqrstuv";

    @Test
    void everyPayloadRoundTrips() {
        JoinRequest request = new JoinRequest(new NodeId("node-2"), "abc123", Secret.of(SECRET), "1.0.0", new byte[] {1, 2, 3});
        assertEquals(request, JoinRequest.read(new PacketReader(request.write())));
        JoinReply reply = new JoinReply(JoinOutcome.ACCEPTED, "1.0.0", new NodeId("node-1"), new byte[] {4}, new byte[] {5});
        assertEquals(reply, JoinReply.read(new PacketReader(reply.write())));
        JoinReply refused = JoinReply.refused(JoinOutcome.INVALID_TOKEN, "1.0.0", new NodeId("node-1"));
        assertEquals(refused, JoinReply.read(new PacketReader(refused.write())));
        NodeHello hello = new NodeHello(new NodeId("node-2"), "1.0.0-beta.1+abc");
        assertEquals(hello, NodeHello.read(new PacketReader(hello.write())));
        NodeWelcome welcome = new NodeWelcome(HelloOutcome.REVOKED, new NodeId("node-1"), "1.0.0");
        assertEquals(welcome, NodeWelcome.read(new PacketReader(welcome.write())));
    }

    @Test
    void theSecretIsNeverPrinted() {
        JoinRequest request = new JoinRequest(new NodeId("node-2"), "abc123", Secret.of(SECRET), "1.0.0", new byte[] {1});
        assertFalse(request.toString().contains(SECRET));
        assertTrue(request.toString().contains("abc123"));
    }

    @Test
    void invalidValuesAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoinRequest(new NodeId("node-2"), "ABC123", Secret.of(SECRET), "1.0.0", new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinRequest(new NodeId("node-2"), "abc123", Secret.of("short"), "1.0.0", new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinRequest(new NodeId("node-2"), "abc123", Secret.of(SECRET), "1.0.0 beta", new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinRequest(new NodeId("node-2"), "abc123", Secret.of(SECRET), "1.0.0", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinReply(JoinOutcome.INVALID_TOKEN, "1.0.0", new NodeId("node-1"), new byte[] {1}, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinReply(JoinOutcome.ACCEPTED, "1.0.0", new NodeId("node-1"), new byte[0], new byte[0]));
    }

    @Test
    void brokenPayloadsThrowOnlyProtocolException() {
        byte[] valid = new NodeHello(new NodeId("node-2"), "1.0.0").write();
        byte[] longer = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(ProtocolException.class, () -> NodeHello.read(new PacketReader(longer)));
        byte[] shorter = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertThrows(ProtocolException.class, () -> NodeHello.read(new PacketReader(shorter)));
        assertThrows(ProtocolException.class, () -> NodeWelcome.read(new PacketReader(new byte[] {99, 0, 0})));
        assertThrows(ProtocolException.class, () -> JoinReply.read(new PacketReader(new byte[] {99})));
        assertThrows(ProtocolException.class, () -> JoinRequest.read(new PacketReader(new byte[0])));
    }
}
