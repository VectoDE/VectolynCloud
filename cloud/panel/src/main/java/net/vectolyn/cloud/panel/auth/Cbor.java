// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of CBOR (RFC 8949) that WebAuthn needs: unsigned and negative integers, byte and text
 * strings, arrays, maps, {@code false}, {@code true} and {@code null}, all with definite lengths.
 * Everything else (indefinite lengths, tags, floats, other simple values) is refused, as are
 * duplicate map keys, more than {@value #MAX_DEPTH} levels, more than {@value #MAX_ITEMS} items and
 * lengths beyond the input. Integers come back as {@code Long}, byte strings as {@code byte[]},
 * text as {@code String}, maps as {@code LinkedHashMap<Object, Object>}.
 */
public final class Cbor {

    /** Deepest nesting. */
    static final int MAX_DEPTH = 16;
    /** Most data items in one input. */
    static final int MAX_ITEMS = 4096;

    private final byte[] data;
    private int pos;
    private int items;

    private Cbor(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    /** The input breaks a rule. */
    public static final class CborException extends Exception {

        private static final long serialVersionUID = 1L;

        CborException(String message) {
            super(message);
        }

        CborException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A decoded item and where it ended.
     *
     * @param value the item
     * @param end   offset after it
     */
    public record Decoded(Object value, int end) {
    }

    /**
     * Decodes exactly one item that fills the whole input.
     *
     * @param data the bytes
     * @return the item
     * @throws CborException if the input is not one supported item
     */
    public static Object decode(byte[] data) throws CborException {
        Decoded decoded = decodeFirst(data, 0);
        if (decoded.end() != data.length) {
            throw new CborException("trailing bytes");
        }
        return decoded.value();
    }

    /**
     * Decodes the first item at an offset, for an item followed by other data (the credential
     * public key inside authenticator data).
     *
     * @param data   the bytes
     * @param offset where the item starts
     * @return the item and its end
     * @throws CborException if there is no supported item
     */
    public static Decoded decodeFirst(byte[] data, int offset) throws CborException {
        if (data == null || offset < 0 || offset > data.length) {
            throw new CborException("no input");
        }
        Cbor reader = new Cbor(data, offset);
        Object value = reader.item(0);
        return new Decoded(value, reader.pos);
    }

    private Object item(int depth) throws CborException {
        if (depth > MAX_DEPTH) {
            throw new CborException("too deep");
        }
        if (++items > MAX_ITEMS) {
            throw new CborException("too many items");
        }
        int initial = next();
        int major = initial >>> 5;
        int info = initial & 0x1f;
        switch (major) {
            case 0 -> {
                return argument(info);
            }
            case 1 -> {
                return -1 - argument(info);
            }
            case 2 -> {
                return bytes(length(info));
            }
            case 3 -> {
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes(length(info)))).toString();
                } catch (CharacterCodingException e) {
                    throw new CborException("invalid UTF-8", e);
                }
            }
            case 4 -> {
                int n = length(info);
                List<Object> list = new ArrayList<>(Math.min(n, 64));
                for (int i = 0; i < n; i++) {
                    list.add(item(depth + 1));
                }
                return list;
            }
            case 5 -> {
                int n = length(info);
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Object key = item(depth + 1);
                    if (!(key instanceof Long) && !(key instanceof String)) {
                        throw new CborException("map key must be an integer or text");
                    }
                    if (map.containsKey(key)) {
                        throw new CborException("duplicate map key");
                    }
                    map.put(key, item(depth + 1));
                }
                return map;
            }
            case 7 -> {
                return switch (info) {
                    case 20 -> false;
                    case 21 -> true;
                    case 22 -> NULL;
                    default -> throw new CborException("unsupported simple value or float");
                };
            }
            default -> throw new CborException("tags are not supported");
        }
    }

    /** CBOR {@code null}, so maps can tell it from a missing key. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private int next() throws CborException {
        if (pos >= data.length) {
            throw new CborException("unexpected end");
        }
        return data[pos++] & 0xff;
    }

    private long argument(int info) throws CborException {
        if (info < 24) {
            return info;
        }
        int size = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new CborException("indefinite or reserved length");
        };
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | next();
        }
        if (size == 8 && value < 0) {
            throw new CborException("integer too large");
        }
        return value;
    }

    private int length(int info) throws CborException {
        long length = argument(info);
        if (length > data.length - pos) {
            throw new CborException("length beyond the input");
        }
        return (int) length;
    }

    private byte[] bytes(int length) throws CborException {
        if (length > data.length - pos) {
            throw new CborException("length beyond the input");
        }
        byte[] out = new byte[length];
        System.arraycopy(data, pos, out, 0, length);
        pos += length;
        return out;
    }
}
