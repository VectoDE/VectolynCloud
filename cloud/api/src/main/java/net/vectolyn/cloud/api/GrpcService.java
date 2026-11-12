// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import net.vectolyn.cloud.api.grpc.ProtoSchema;
import org.jspecify.annotations.Nullable;

/**
 * {@code vectolyn.api.v1.CloudService}: the schema read from the published
 * {@code vectolyn/api/v1/cloud.proto} and the method table of {@link GrpcCalls}. The constructor
 * fails if the two do not list the same methods, so the file and the server cannot drift apart.
 */
final class GrpcService {

    /** Resource path of the proto file, also its import path for clients. */
    static final String PROTO = "vectolyn/api/v1/cloud.proto";

    private final ProtoSchema schema;
    private final Map<String, GrpcCalls.Call> calls;

    GrpcService(RouteCaller routes) {
        this.schema = load();
        this.calls = new GrpcCalls(routes).table();
        Set<String> served = new java.util.TreeSet<>(calls.keySet());
        served.add("Events");
        if (!served.equals(new java.util.TreeSet<>(schema.methods().keySet()))) {
            throw new IllegalStateException("cloud.proto and GrpcCalls list different methods");
        }
    }

    /** Reads the proto file from the class path. */
    static ProtoSchema load() {
        try (InputStream in = GrpcService.class.getClassLoader().getResourceAsStream(PROTO)) {
            if (in == null) {
                throw new IllegalStateException(PROTO + " is missing");
            }
            return ProtoSchema.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ProtoSchema schema() {
        return schema;
    }

    /**
     * The method of a request path.
     *
     * @param path {@code /vectolyn.api.v1.CloudService/<Method>}
     * @return the method, or null for an unknown path
     */
    ProtoSchema.@Nullable Method method(String path) {
        String prefix = "/" + schema.pkg() + "." + schema.service() + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        return schema.methods().get(path.substring(prefix.length()));
    }

    GrpcCalls.@Nullable Call call(String method) {
        return calls.get(method);
    }
}
