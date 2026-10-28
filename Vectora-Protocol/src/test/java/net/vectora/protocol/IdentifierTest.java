package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation rules of the identifier value types. */
class IdentifierTest {

    @ParameterizedTest
    @ValueSource(strings = {"Lobby-1", "a", "BedWars_4x4-12", "0"})
    void acceptsValidServiceIds(String value) {
        assertEquals(value, new ServiceId(value).value());
        assertEquals(value, new ServiceId(value).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-Lobby", "_x", "Lobby 1", "Lobby.1", "../etc", "Lobby/1", "Lobbyä1"})
    void rejectsInvalidServiceIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ServiceId(value));
    }

    @Test
    void serviceIdLengthLimit() {
        assertEquals(ServiceId.MAX_LENGTH, new ServiceId("a".repeat(ServiceId.MAX_LENGTH)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new ServiceId("a".repeat(ServiceId.MAX_LENGTH + 1)));
    }

    @Test
    void groupNameRules() {
        assertEquals("Lobby", new GroupName("Lobby").value());
        assertEquals(GroupName.MAX_LENGTH, new GroupName("a".repeat(GroupName.MAX_LENGTH)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new GroupName("a".repeat(GroupName.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> new GroupName("Sky Wars"));
        assertThrows(IllegalArgumentException.class, () -> new GroupName(""));
    }

    @Test
    void nodeIdRules() {
        assertEquals("node-1", new NodeId("node-1").value());
        assertEquals(PeerRole.NODE, new NodeId("node-1").role());
        assertEquals(PeerRole.SERVICE, new ServiceId("Lobby-1").role());
        assertThrows(IllegalArgumentException.class, () -> new NodeId("node 1"));
        assertThrows(IllegalArgumentException.class, () -> new NodeId("a".repeat(NodeId.MAX_LENGTH + 1)));
    }

    @Test
    void channelNameRules() {
        assertEquals("vectora.lobby_nav-1", new ChannelName("vectora.lobby_nav-1").value());
        assertEquals(ChannelName.MAX_LENGTH,
                new ChannelName("a".repeat(ChannelName.MAX_LENGTH)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("a".repeat(ChannelName.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("Vectora"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName(""));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("a b"));
    }

    @Test
    void authTokenNeverShowsItsValue() {
        AuthToken token = new AuthToken("s3cr3t-token-value-0123456789");
        assertFalse(token.toString().contains("s3cr3t"));
        assertEquals("AuthToken[redacted]", token.toString());
    }

    @Test
    void authTokenComparesByValue() {
        AuthToken token = new AuthToken("s3cr3t-token-value-0123456789");
        assertTrue(token.matches(new AuthToken("s3cr3t-token-value-0123456789")));
        assertFalse(token.matches(new AuthToken("s3cr3t-token-value-0123456780")));
        assertEquals(token, new AuthToken("s3cr3t-token-value-0123456789"));
        assertEquals(token.hashCode(), new AuthToken("s3cr3t-token-value-0123456789").hashCode());
    }

    @Test
    void authTokenLengthAndCharacters() {
        assertEquals(AuthToken.MIN_LENGTH, new AuthToken("a".repeat(AuthToken.MIN_LENGTH)).value().length());
        assertEquals(AuthToken.MAX_LENGTH, new AuthToken("a".repeat(AuthToken.MAX_LENGTH)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new AuthToken("a".repeat(AuthToken.MIN_LENGTH - 1)));
        assertThrows(IllegalArgumentException.class, () -> new AuthToken("a".repeat(AuthToken.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> new AuthToken("a".repeat(20) + " " + "b".repeat(20)));
        assertThrows(IllegalArgumentException.class, () -> new AuthToken("a".repeat(20) + "ä" + "b".repeat(20)));
    }

    @Test
    void peerIdRoundTripKeepsTheRole() {
        for (PeerId peer : new PeerId[] {new ServiceId("Lobby-1"), new NodeId("node-1")}) {
            PacketWriter out = new PacketWriter();
            peer.writeWithRole(out);
            PacketReader in = new PacketReader(out.toByteArray());
            assertEquals(peer, PeerId.readWithRole(in));
            in.expectEnd();
        }
    }

    @Test
    void unknownPeerRoleIsAProtocolError() {
        byte[] bytes = new PacketWriter().writeByte(9).writeString("x", 64).toByteArray();
        assertThrows(ProtocolException.class, () -> PeerId.readWithRole(new PacketReader(bytes)));
    }
}
