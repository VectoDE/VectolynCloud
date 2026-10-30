package net.vectolyn.protocol;

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
    @ValueSource(strings = {"lobby-1", "a", "bedwars_4x4-12", "0", "con1", "console", "com10", "lpt", "nul_1"})
    void acceptsValidServiceIds(String value) {
        assertEquals(value, new ServiceId(value).value());
        assertEquals(value, new ServiceId(value).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-lobby", "_x", "lobby 1", "lobby.1", "../etc", "lobby/1", "lobbyä1",
        "Lobby-1", "lobby-A", "BEDWARS"})
    void rejectsInvalidServiceIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ServiceId(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lobby", "lobbY", "-lobby", "_lobby", "lobby 1", "lobby.1", ""})
    void rejectsInvalidGroupNamesAndNodeIds(String value) {
        assertThrows(IllegalArgumentException.class, () -> new GroupName(value));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"con", "prn", "aux", "nul", "com0", "com1", "com9", "lpt0", "lpt1", "lpt9"})
    void rejectsWindowsDeviceNames(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ServiceId(value));
        assertThrows(IllegalArgumentException.class, () -> new GroupName(value));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"con1", "console", "auxiliary", "com10", "lpt", "nul-1", "prn_"})
    void acceptsNamesThatOnlyStartLikeADeviceName(String value) {
        assertEquals(value, new ServiceId(value).value());
        assertEquals(value, new GroupName(value).value());
        assertEquals(value, new NodeId(value).value());
    }

    @Test
    void lengthLimits() {
        assertEquals(32, GroupName.MAX_LENGTH);
        assertEquals(32, NodeId.MAX_LENGTH);
        assertEquals(64, ServiceId.MAX_LENGTH);

        assertEquals(32, new GroupName("a".repeat(32)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new GroupName("a".repeat(33)));
        assertEquals(32, new NodeId("a".repeat(32)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new NodeId("a".repeat(33)));
        assertEquals(64, new ServiceId("a".repeat(64)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new ServiceId("a".repeat(65)));
    }

    @Test
    void serviceIdOfAFullLengthGroupFits() {
        String group = new GroupName("g".repeat(GroupName.MAX_LENGTH)).value();
        assertEquals(group + "-1000", new ServiceId(group + "-1000").value());
    }

    @Test
    void groupNameRules() {
        assertEquals("lobby", new GroupName("lobby").value());
        assertThrows(IllegalArgumentException.class, () -> new GroupName("sky wars"));
    }

    @Test
    void nodeIdRules() {
        assertEquals("node-1", new NodeId("node-1").value());
        assertEquals(PeerRole.NODE, new NodeId("node-1").role());
        assertEquals(PeerRole.SERVICE, new ServiceId("lobby-1").role());
        assertThrows(IllegalArgumentException.class, () -> new NodeId("node 1"));
    }

    @Test
    void channelNameRules() {
        assertEquals("vectolyn.lobby_nav-1", new ChannelName("vectolyn.lobby_nav-1").value());
        assertEquals(ChannelName.MAX_LENGTH,
                new ChannelName("a".repeat(ChannelName.MAX_LENGTH)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("a".repeat(ChannelName.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("Vectolyn"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName(""));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("a b"));
        // No dot names and nothing that looks like a hidden file or an option.
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("."));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName(".."));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName(".hidden"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("-x"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelName("--help"));
        // Counter-check: the same characters further inside or at the end stay valid.
        assertEquals("_internal", new ChannelName("_internal").value());
        assertEquals("a.", new ChannelName("a.").value());
        assertEquals("a-", new ChannelName("a-").value());
        assertEquals("0..x", new ChannelName("0..x").value());
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
        for (PeerId peer : new PeerId[] {new ServiceId("lobby-1"), new NodeId("node-1")}) {
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
