package net.vectora.protocol.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
        expected.put(ServiceReady.class, 0x10);
        expected.put(ServiceStatus.class, 0x11);
        expected.put(RegisterServer.class, 0x20);
        expected.put(UnregisterServer.class, 0x21);
        expected.put(PlayerJoin.class, 0x30);
        expected.put(PlayerQuit.class, 0x31);
        expected.put(PlayerSwitch.class, 0x32);
        expected.put(ChannelMessage.class, 0x40);
        expected.put(Request.class, 0x50);
        expected.put(Response.class, 0x51);
        expected.forEach((type, id) -> {
            assertEquals(id, registry.typeOf(type).id(), type.getSimpleName());
            assertEquals(type, registry.byId(id).orElseThrow().type());
        });
        assertEquals(expected.size(), registry.types().size());
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
