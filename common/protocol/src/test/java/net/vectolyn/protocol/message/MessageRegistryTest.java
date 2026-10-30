package net.vectolyn.protocol.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.vectolyn.protocol.Protocol;
import net.vectolyn.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;

class MessageRegistryTest {

    private final MessageRegistry registry = MessageRegistry.standard();

    @Test
    void idsAreUnique() {
        List<Integer> ids = registry.types().stream().map(MessageType::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "duplicate message id in " + ids);
    }

    @Test
    void everyMessageTypeIsRegistered() {
        Set<Class<?>> permitted = Set.copyOf(Arrays.asList(Message.class.getPermittedSubclasses()));
        Set<Class<?>> registered = registry.types().stream().map(MessageType::type).collect(Collectors.toSet());
        assertEquals(permitted, registered);
    }

    @Test
    void everySampleCoversARegisteredType() {
        Set<Class<?>> sampled = SampleMessages.typical().stream().map(Object::getClass).collect(Collectors.toSet());
        Set<Class<?>> registered = registry.types().stream().map(MessageType::type).collect(Collectors.toSet());
        assertEquals(registered, sampled);
    }

    /** The ids are part of the wire format. Changing one needs a new major version. */
    @Test
    void idsMatchTheDocumentedTable() {
        Map<Class<? extends Message>, Integer> expected = new LinkedHashMap<>();
        expected.put(Hello.class, 0x00);
        expected.put(AuthResult.class, 0x01);
        expected.put(Ping.class, 0x02);
        expected.put(Pong.class, 0x03);
        expected.put(Disconnect.class, 0x04);
        expected.put(SessionTicket.class, 0x05);
        expected.put(ServiceReady.class, 0x10);
        expected.put(ServiceStatus.class, 0x11);
        expected.put(RegisterServer.class, 0x20);
        expected.put(UnregisterServer.class, 0x21);
        expected.put(ServerCapacity.class, 0x22);
        expected.put(PlayerJoin.class, 0x30);
        expected.put(PlayerQuit.class, 0x31);
        expected.put(PlayerSwitch.class, 0x32);
        expected.put(TransferPlayer.class, 0x33);
        expected.put(NetworkKick.class, 0x34);
        expected.put(ChannelMessage.class, 0x40);
        expected.put(Request.class, 0x50);
        expected.put(Response.class, 0x51);
        expected.forEach((type, id) -> {
            assertEquals(id, registry.typeOf(type).id(), type.getSimpleName());
            assertEquals(type, registry.byId(id).orElseThrow().type());
        });
        assertEquals(expected.size(), registry.types().size());
    }

    /**
     * Version 1.1 added three messages and 1.2 one; an older connection never carries them,
     * everything else is 1.0.
     */
    @Test
    void newMessagesAreOnlyAvailableFromTheVersionThatAddedThem() {
        ProtocolVersion v10 = new ProtocolVersion(1, 0);
        ProtocolVersion v11 = new ProtocolVersion(1, 1);
        ProtocolVersion v12 = new ProtocolVersion(1, 2);
        Set<Class<?>> since11 = Set.of(ServerCapacity.class, TransferPlayer.class, NetworkKick.class);
        Set<Class<?>> since12 = Set.of(SessionTicket.class);
        for (MessageType<?> type : registry.types()) {
            String name = type.type().getSimpleName();
            ProtocolVersion since = since12.contains(type.type()) ? v12 : since11.contains(type.type()) ? v11 : v10;
            assertEquals(since, type.since(), name);
            assertEquals(since.equals(v10), type.availableIn(v10), name);
            assertEquals(!since.equals(v12), type.availableIn(v11), name);
            assertTrue(type.availableIn(v12), name);
            assertTrue(type.availableIn(new ProtocolVersion(1, 9)), name);
            assertFalse(type.availableIn(new ProtocolVersion(2, 1)), name);
        }
        assertEquals(v12, Protocol.PROTOCOL_VERSION);
    }

    @Test
    void typesAreListedInIdOrder() {
        List<Integer> ids = registry.types().stream().map(MessageType::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    void builderRejectsDuplicates() {
        MessageRegistry.Builder builder = MessageRegistry.builder().register(1, Ping.class, Ping::read);
        assertThrows(IllegalArgumentException.class, () -> builder.register(1, Pong.class, Pong::read));
        assertThrows(IllegalArgumentException.class, () -> builder.register(2, Ping.class, Ping::read));
        assertThrows(IllegalArgumentException.class, () -> builder.register(-1, Pong.class, Pong::read));
    }

    @Test
    void unknownLookups() {
        assertTrue(registry.byId(0x7F).isEmpty());
        MessageRegistry pingOnly = MessageRegistry.builder().register(1, Ping.class, Ping::read).build();
        assertThrows(IllegalArgumentException.class, () -> pingOnly.typeOf(Pong.class));
        assertThrows(IllegalArgumentException.class, () -> pingOnly.typeOf(new Pong(1)));
    }
}
