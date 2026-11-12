// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.api.grpc.Proto;
import net.vectolyn.cloud.api.grpc.ProtoSchema;

/**
 * Fuzz targets for the gRPC listener's readers of untrusted bytes: the request framing, the
 * message decoder against every request message of {@code cloud.proto}, and the reader of the
 * {@code .proto} subset. Each either returns a value or fails with its one documented exception;
 * a decoded message without unknown enum numbers encodes again, and after defaults written on the
 * wire are dropped once, encoding and decoding no longer change it. Regression mode in every test
 * run; the nightly workflow fuzzes them on Linux.
 */
class GrpcFuzzTest {

    private static final ProtoSchema SCHEMA = GrpcService.load();
    private static final List<Proto.Message> REQUESTS = SCHEMA.methods().values().stream()
            .map(ProtoSchema.Method::request).distinct().toList();

    @FuzzTest
    void anyBodyIsOneMessageOrAFramingError(byte @NotNull [] body) {
        try {
            byte[] message = GrpcStatus.unframe(body);
            assertEquals(body.length - 5, message.length);
            assertEquals(0, body[0]);
        } catch (GrpcStatus.FramingException e) {
            assertTrue(e.status() == GrpcStatus.INTERNAL || e.status() == GrpcStatus.UNIMPLEMENTED);
        }
    }

    @FuzzTest
    void anyMessageDecodesOrIsRefused(byte @NotNull [] input) {
        if (input.length == 0) {
            return;
        }
        Proto.Message request = REQUESTS.get((input[0] & 0xFF) % REQUESTS.size());
        byte[] bytes = java.util.Arrays.copyOfRange(input, 1, input.length);
        Map<String, Object> decoded;
        try {
            decoded = Proto.decode(request, bytes);
        } catch (Proto.ProtoException e) {
            assertNotNull(e.getMessage());
            return;
        }
        if (!hasUnknownEnum(request, decoded)) {
            try {
                // Defaults written on the wire disappear once; after that the value is stable.
                Map<String, Object> normal = Proto.decode(request, Proto.encode(request, decoded));
                assertEquals(normal, Proto.decode(request, Proto.encode(request, normal)));
            } catch (Proto.ProtoException e) {
                throw new AssertionError("a re-encoded message must decode", e);
            }
        }
    }

    @FuzzTest
    void anyProtoFileIsReadOrRefused(byte @NotNull [] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        try {
            ProtoSchema schema = ProtoSchema.parse(text);
            assertNotNull(schema.service());
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
        }
    }

    private static boolean hasUnknownEnum(Proto.Message message, Map<String, Object> value) {
        for (Proto.Field field : message.fields()) {
            Object v = value.get(field.name());
            if (v == null) {
                continue;
            }
            for (Object item : field.repeated() ? (List<?>) v : List.of(v)) {
                if (field.type() == Proto.Type.ENUM && !field.values().contains(item)) {
                    return true;
                }
                if (field.type() == Proto.Type.MESSAGE && item instanceof Map<?, ?> nested) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = (Map<String, Object>) nested;
                    if (hasUnknownEnum(field.message(), inner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
