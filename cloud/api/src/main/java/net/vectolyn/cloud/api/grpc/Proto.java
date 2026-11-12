// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small Protocol Buffers (proto3) codec driven by message descriptions instead of generated
 * classes. It covers what {@code cloud.proto} uses: {@code string}, {@code bool}, {@code int32},
 * {@code int64}, enums, nested messages, {@code repeated} (packed or not) and {@code optional}.
 *
 * <p>Decoded messages are maps from the proto field name to {@link String}, {@link Boolean},
 * {@link Integer}, {@link Long}, nested maps and lists. The decoder reads untrusted bytes, so it
 * is strict where the format allows it: unknown fields are skipped (proto3), but groups, truncated
 * values, over-long varints, invalid UTF-8, wrong wire types for a known field and nesting deeper
 * than {@value #MAX_DEPTH} fail with {@link ProtoException}; nothing else is thrown.
 */
public final class Proto {

    /** Deepest nesting of messages. */
    public static final int MAX_DEPTH = 8;
    /** Most elements of one repeated field. */
    public static final int MAX_REPEATED = 1024;

    private Proto() {
    }

    /** Field types. */
    public enum Type {
        /** {@code string}, UTF-8. */
        STRING,
        /** {@code bool}. */
        BOOL,
        /** {@code int32}. */
        INT32,
        /** {@code int64}. */
        INT64,
        /** An enum: an {@code int32} on the wire, its value name in a map. */
        ENUM,
        /** A nested message. */
        MESSAGE
    }

    /**
     * One field.
     *
     * @param number   field number
     * @param name     proto name (snake_case)
     * @param type     the type
     * @param repeated whether it is {@code repeated}
     * @param optional whether it is {@code optional} (presence is kept, also for a default value)
     * @param message  the message of a {@link Type#MESSAGE} field, or null
     * @param values   the value names of an {@link Type#ENUM} field by number, or null
     */
    public record Field(int number, String name, Type type, boolean repeated, boolean optional, Message message,
                        List<String> values) {

        /** Checks the description. */
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (number < 1 || number > 536_870_911) {
                throw new IllegalArgumentException("field number");
            }
            values = values == null ? null : List.copyOf(values);
        }
    }

    /**
     * A message: its name and fields. Fields of nested messages may be added after creation, so
     * a message can refer to itself.
     */
    public static final class Message {

        private final String name;
        private final Map<Integer, Field> byNumber = new LinkedHashMap<>();

        /**
         * Creates an empty message.
         *
         * @param name the proto name
         */
        public Message(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** The proto name. */
        public String name() {
            return name;
        }

        /** The fields in declaration order. */
        public List<Field> fields() {
            return List.copyOf(byNumber.values());
        }

        /**
         * Adds a field.
         *
         * @param field the field
         * @return this
         */
        public Message add(Field field) {
            if (byNumber.putIfAbsent(field.number(), field) != null) {
                throw new IllegalArgumentException("field number twice in " + name + ": " + field.number());
            }
            return this;
        }

        Field field(int number) {
            return byNumber.get(number);
        }
    }

    // ---------------------------------------------------------------- encoding

    /**
     * Encodes a message from a map keyed by proto field names. Missing keys and {@code null}
     * values are left out; a proto3 default ({@code ""}, {@code 0}, {@code false}) is left out
     * unless the field is {@code optional}.
     *
     * @param message the description
     * @param value   the values
     * @return the bytes
     * @throws IllegalArgumentException if a value has the wrong Java type
     */
    public static byte[] encode(Message message, Map<String, ?> value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, message, value, 0);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Message message, Map<String, ?> value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nested too deep");
        }
        for (Field field : message.fields()) {
            Object v = value.get(field.name());
            if (v == null) {
                continue;
            }
            if (field.repeated()) {
                if (!(v instanceof List<?> list)) {
                    throw new IllegalArgumentException(field.name() + " must be a list");
                }
                for (Object item : list) {
                    if (item != null) {
                        writeOne(out, field, item, depth, true);
                    }
                }
            } else {
                writeOne(out, field, v, depth, field.optional());
            }
        }
    }

    private static void writeOne(ByteArrayOutputStream out, Field field, Object v, int depth, boolean keepDefault) {
        switch (field.type()) {
            case STRING -> {
                byte[] bytes = string(field, v).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0 || keepDefault) {
                    tag(out, field.number(), 2);
                    varint(out, bytes.length);
                    out.writeBytes(bytes);
                }
            }
            case BOOL -> {
                if (!(v instanceof Boolean b)) {
                    throw new IllegalArgumentException(field.name() + " must be a boolean");
                }
                if (b || keepDefault) {
                    tag(out, field.number(), 0);
                    varint(out, b ? 1 : 0);
                }
            }
            case INT32, INT64 -> {
                if (!(v instanceof Integer || v instanceof Long || v instanceof Short)) {
                    throw new IllegalArgumentException(field.name() + " must be a whole number");
                }
                long n = ((Number) v).longValue();
                if (field.type() == Type.INT32 && (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)) {
                    throw new IllegalArgumentException(field.name() + " is out of range");
                }
                if (n != 0 || keepDefault) {
                    tag(out, field.number(), 0);
                    varint(out, n);
                }
            }
            case ENUM -> {
                int n = field.values().indexOf(string(field, v));
                if (n < 0) {
                    throw new IllegalArgumentException(field.name() + " has an unknown value");
                }
                if (n != 0 || keepDefault) {
                    tag(out, field.number(), 0);
                    varint(out, n);
                }
            }
            case MESSAGE -> {
                if (!(v instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException(field.name() + " must be a message");
                }
                @SuppressWarnings("unchecked")
                Map<String, ?> nested = (Map<String, ?>) map;
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                write(inner, field.message(), nested, depth + 1);
                tag(out, field.number(), 2);
                varint(out, inner.size());
                out.writeBytes(inner.toByteArray());
            }
        }
    }

    private static String string(Field field, Object v) {
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(field.name() + " must be a string");
        }
        return s;
    }

    private static void tag(ByteArrayOutputStream out, int number, int wireType) {
        varint(out, ((long) number << 3) | wireType);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    // ---------------------------------------------------------------- decoding

    /**
     * Decodes a message. Fields that are absent stay absent from the map (proto3: the reader
     * applies the default); repeated fields are lists.
     *
     * @param message the description
     * @param bytes   the bytes
     * @return the values by proto field name
     * @throws ProtoException if the bytes are not a valid encoding of the message
     */
    public static Map<String, Object> decode(Message message, byte[] bytes) throws ProtoException {
        return read(message, new Reader(bytes, 0, bytes.length), 0);
    }

    private static Map<String, Object> read(Message message, Reader in, int depth) throws ProtoException {
        if (depth > MAX_DEPTH) {
            throw new ProtoException("messages are nested too deep");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        while (in.more()) {
            long key = in.varint();
            int wireType = (int) (key & 7);
            long number = key >>> 3;
            if (number < 1 || number > 536_870_911) {
                throw new ProtoException("invalid field number");
            }
            Field field = message.field((int) number);
            if (field == null) {
                in.skip(wireType);
                continue;
            }
            boolean varintType = field.type() == Type.BOOL || field.type() == Type.INT32
                    || field.type() == Type.INT64 || field.type() == Type.ENUM;
            if (field.repeated() && varintType && wireType == 2) {
                Reader packed = in.sub();
                while (packed.more()) {
                    add(result, field, scalar(field, packed));
                }
                continue;
            }
            int expected = varintType ? 0 : 2;
            if (wireType != expected) {
                throw new ProtoException("wrong wire type for " + field.name());
            }
            Object value = switch (field.type()) {
                case STRING -> in.string();
                case MESSAGE -> read(field.message(), in.sub(), depth + 1);
                default -> scalar(field, in);
            };
            if (field.repeated()) {
                add(result, field, value);
            } else if (field.type() == Type.MESSAGE && result.get(field.name()) instanceof Map<?, ?> earlier) {
                // proto3: a message field seen twice is merged
                @SuppressWarnings("unchecked")
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) earlier);
                @SuppressWarnings("unchecked")
                Map<String, Object> later = (Map<String, Object>) value;
                merged.putAll(later);
                result.put(field.name(), merged);
            } else {
                result.put(field.name(), value); // last one wins
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void add(Map<String, Object> result, Field field, Object value) throws ProtoException {
        List<Object> list = (List<Object>) result.computeIfAbsent(field.name(), k -> new ArrayList<>());
        if (list.size() >= MAX_REPEATED) {
            throw new ProtoException(field.name() + " has more than " + MAX_REPEATED + " elements");
        }
        list.add(value);
    }

    private static Object scalar(Field field, Reader in) throws ProtoException {
        long v = in.varint();
        return switch (field.type()) {
            case BOOL -> v != 0;
            case INT32 -> (int) v; // proto3: int32 is sign-extended to 64 bits and cut back
            case INT64 -> v;
            case ENUM -> {
                int n = (int) v;
                yield n >= 0 && n < field.values().size() ? field.values().get(n) : Integer.toString(n);
            }
            default -> throw new ProtoException("not a scalar: " + field.name());
        };
    }

    /** Thrown for bytes that are not a valid message. */
    public static final class ProtoException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message what is wrong (no input in it)
         */
        public ProtoException(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Reader {

        private final byte[] bytes;
        private int position;
        private final int end;

        Reader(byte[] bytes, int position, int end) {
            this.bytes = bytes;
            this.position = position;
            this.end = end;
        }

        boolean more() {
            return position < end;
        }

        long varint() throws ProtoException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) {
                    throw new ProtoException("truncated varint");
                }
                byte b = bytes[position++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new ProtoException("varint longer than 10 bytes");
        }

        Reader sub() throws ProtoException {
            int length = length();
            Reader sub = new Reader(bytes, position, position + length);
            position += length;
            return sub;
        }

        String string() throws ProtoException {
            int length = length();
            try {
                String s = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes, position, length))
                        .toString();
                position += length;
                return s;
            } catch (CharacterCodingException e) {
                throw new ProtoException("invalid UTF-8");
            }
        }

        private int length() throws ProtoException {
            long length = varint();
            if (length < 0 || length > end - position) {
                throw new ProtoException("length beyond the end");
            }
            return (int) length;
        }

        void skip(int wireType) throws ProtoException {
            switch (wireType) {
                case 0 -> varint();
                case 1 -> fixed(8);
                case 2 -> {
                    int length = length(); // reads the length first, then moves past the bytes
                    position += length;
                }
                case 5 -> fixed(4);
                default -> throw new ProtoException("unsupported wire type " + wireType);
            }
        }

        private void fixed(int n) throws ProtoException {
            if (end - position < n) {
                throw new ProtoException("truncated value");
            }
            position += n;
        }
    }
}
