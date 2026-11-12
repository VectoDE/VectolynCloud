// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import java.nio.charset.StandardCharsets;

/**
 * gRPC status codes, the length-prefixed message framing and the trailers.
 */
final class GrpcStatus {

    static final int OK = 0;
    static final int UNKNOWN = 2;
    static final int INVALID_ARGUMENT = 3;
    static final int DEADLINE_EXCEEDED = 4;
    static final int NOT_FOUND = 5;
    static final int PERMISSION_DENIED = 7;
    static final int RESOURCE_EXHAUSTED = 8;
    static final int FAILED_PRECONDITION = 9;
    static final int UNIMPLEMENTED = 12;
    static final int INTERNAL = 13;
    static final int UNAVAILABLE = 14;
    static final int UNAUTHENTICATED = 16;

    private GrpcStatus() {
    }

    /**
     * The gRPC status for the HTTP status of a REST error.
     *
     * @param http the HTTP status
     * @return the gRPC status
     */
    static int of(int http) {
        return switch (http) {
            case 400, 415, 422 -> INVALID_ARGUMENT;
            case 401 -> UNAUTHENTICATED;
            case 403 -> PERMISSION_DENIED;
            case 404 -> NOT_FOUND;
            case 408 -> DEADLINE_EXCEEDED;
            case 405, 426, 501 -> UNIMPLEMENTED;
            case 409, 412 -> FAILED_PRECONDITION;
            case 413, 429 -> RESOURCE_EXHAUSTED;
            case 500 -> INTERNAL;
            case 503 -> UNAVAILABLE;
            default -> UNKNOWN;
        };
    }

    /**
     * Trailers with a status.
     *
     * @param status  the gRPC status
     * @param message the message, or null
     * @param code    the REST error code for {@code vectolyn-code}, or null
     * @return the headers
     */
    static Http2Headers trailers(int status, String message, String code) {
        Http2Headers headers = new DefaultHttp2Headers().set("grpc-status", Integer.toString(status));
        if (message != null && !message.isEmpty()) {
            headers.set("grpc-message", percentEncode(message));
        }
        if (code != null && code.matches("[a-z0-9-]{1,64}")) {
            headers.set("vectolyn-code", code);
        }
        return headers;
    }

    /** {@code grpc-message} is percent-encoded UTF-8: everything outside printable ASCII and {@code %}. */
    static String percentEncode(String message) {
        StringBuilder out = new StringBuilder();
        for (byte b : message.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 0x20 && c <= 0x7E && c != '%') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * Frames one message: a zero flag byte (not compressed), the length as 4 bytes big-endian, the
     * message.
     *
     * @param message the encoded message
     * @return the frame
     */
    static byte[] frame(byte[] message) {
        byte[] out = new byte[message.length + 5];
        out[1] = (byte) (message.length >>> 24);
        out[2] = (byte) (message.length >>> 16);
        out[3] = (byte) (message.length >>> 8);
        out[4] = (byte) message.length;
        System.arraycopy(message, 0, out, 5, message.length);
        return out;
    }

    /**
     * Reads the one message of a unary request or of {@code Events}.
     *
     * @param body every byte of the request stream
     * @return the message
     * @throws FramingException if there is not exactly one complete, uncompressed message
     */
    static byte[] unframe(byte[] body) throws FramingException {
        if (body.length < 5) {
            throw new FramingException(INTERNAL, "The request has no complete message.");
        }
        if (body[0] != 0) {
            throw new FramingException(body[0] == 1 ? UNIMPLEMENTED : INTERNAL,
                    body[0] == 1 ? "Compressed messages are not supported." : "Invalid message flag.");
        }
        long length = ((body[1] & 0xFFL) << 24) | ((body[2] & 0xFFL) << 16) | ((body[3] & 0xFFL) << 8) | (body[4] & 0xFFL);
        if (length != body.length - 5L) {
            throw new FramingException(INTERNAL, "The request must hold exactly one complete message.");
        }
        byte[] message = new byte[body.length - 5];
        System.arraycopy(body, 5, message, 0, message.length);
        return message;
    }

    /** A request whose framing is broken. */
    static final class FramingException extends Exception {

        private static final long serialVersionUID = 1L;
        private final int status;

        FramingException(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
