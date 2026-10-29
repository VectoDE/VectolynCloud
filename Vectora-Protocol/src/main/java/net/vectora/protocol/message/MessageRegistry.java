package net.vectora.protocol.message;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.ProtocolVersion;

/**
 * Maps message ids to message types and back. Immutable once built; ids and types are unique.
 *
 * <p>The ids of {@link #standard()} are part of the wire format (table in
 * {@code docs/cloud/protocol.md}). Changing an existing id needs a new major protocol version;
 * a new message gets a new id and a new minor version.
 */
public final class MessageRegistry {

    /** Version 1.1 added {@link ServerCapacity} and {@link TransferPlayer}. */
    private static final ProtocolVersion V1_1 = new ProtocolVersion(1, 1);

    private static final MessageRegistry STANDARD = builder()
            .register(0x00, Hello.class, Hello::read)
            .register(0x01, AuthResult.class, AuthResult::read)
            .register(0x02, Ping.class, Ping::read)
            .register(0x03, Pong.class, Pong::read)
            .register(0x04, Disconnect.class, Disconnect::read)
            .register(0x10, ServiceReady.class, ServiceReady::read)
            .register(0x11, ServiceStatus.class, ServiceStatus::read)
            .register(0x20, RegisterServer.class, RegisterServer::read)
            .register(0x21, UnregisterServer.class, UnregisterServer::read)
            .register(0x22, ServerCapacity.class, ServerCapacity::read, V1_1)
            .register(0x30, PlayerJoin.class, PlayerJoin::read)
            .register(0x31, PlayerQuit.class, PlayerQuit::read)
            .register(0x32, PlayerSwitch.class, PlayerSwitch::read)
            .register(0x33, TransferPlayer.class, TransferPlayer::read, V1_1)
            .register(0x40, ChannelMessage.class, ChannelMessage::read)
            .register(0x50, Request.class, Request::read)
            .register(0x51, Response.class, Response::read)
            .build();

    private final Map<Integer, MessageType<?>> byId;
    private final Map<Class<?>, MessageType<?>> byType;

    private MessageRegistry(Map<Integer, MessageType<?>> byId) {
        this.byId = Collections.unmodifiableMap(new TreeMap<>(byId));
        Map<Class<?>, MessageType<?>> types = new HashMap<>();
        byId.values().forEach(entry -> types.put(entry.type(), entry));
        this.byType = Map.copyOf(types);
    }

    /** Returns the registry with every message of the current protocol version. */
    public static MessageRegistry standard() {
        return STANDARD;
    }

    /** Starts an empty registry, for tests or a later protocol extension. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the type registered under {@code id}, if any. */
    public Optional<MessageType<?>> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Returns the entry for a message class.
     *
     * @throws IllegalArgumentException if the class is not registered
     */
    @SuppressWarnings("unchecked") // byType only maps a class to an entry of the same class.
    public <T extends Message> MessageType<T> typeOf(Class<T> type) {
        MessageType<?> entry = byType.get(type);
        if (entry == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " is not registered");
        }
        return (MessageType<T>) entry;
    }

    /**
     * Returns the entry for the class of {@code message}.
     *
     * @throws IllegalArgumentException if the class is not registered
     */
    public MessageType<?> typeOf(Message message) {
        return typeOf(message.getClass());
    }

    /** Returns all entries, ordered by id. */
    public Collection<MessageType<?>> types() {
        return byId.values();
    }

    /** Collects entries and refuses duplicate ids or types. */
    public static final class Builder {

        private final Map<Integer, MessageType<?>> byId = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a message type.
         *
         * @throws IllegalArgumentException if the id is negative or the id or type is already
         *     registered
         */
        public <T extends Message> Builder register(int id, Class<T> type, Function<PacketReader, T> reader) {
            return register(id, type, reader, MessageType.FIRST);
        }

        /**
         * Adds a message type that a later minor version introduced.
         *
         * @throws IllegalArgumentException if the id is negative or the id or type is already
         *     registered
         */
        public <T extends Message> Builder register(int id, Class<T> type, Function<PacketReader, T> reader,
                                                    ProtocolVersion since) {
            MessageType<T> entry = new MessageType<>(id, type, reader, since);
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("message id " + id + " is already used by "
                        + byId.get(id).type().getSimpleName());
            }
            if (byId.values().stream().anyMatch(existing -> existing.type() == type)) {
                throw new IllegalArgumentException(type.getSimpleName() + " is already registered");
            }
            byId.put(id, entry);
            return this;
        }

        /** Returns the registry. */
        public MessageRegistry build() {
            return new MessageRegistry(byId);
        }
    }
}
