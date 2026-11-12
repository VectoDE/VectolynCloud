// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.cloud.config.model.ApiConfig;

/**
 * Where and how the API server listens.
 *
 * @param bind        address to bind to
 * @param port        port, 0 for any free port (tests)
 * @param tls         key material for HTTPS, or empty for plain HTTP (loopback only)
 * @param corsOrigins origins a browser may call from; empty switches CORS off
 * @param limits      limits
 * @param graphqlIntrospection whether the GraphQL endpoint answers introspection
 *                    ({@code api.graphql-introspection})
 * @param grpcPort    port of the gRPC listener ({@code api.grpc-port}), 0 when it is off, -1 for
 *                    any free port (tests)
 */
public record ApiSettings(String bind, int port, Optional<ApiTls.Material> tls, List<String> corsOrigins,
                          ApiLimits limits, boolean graphqlIntrospection, int grpcPort) {

    /** Checks and copies the values. */
    public ApiSettings {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(limits, "limits");
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        if (grpcPort < -1 || grpcPort > 65535) {
            throw new IllegalArgumentException("gRPC port out of range");
        }
    }

    /** Settings with GraphQL introspection off. */
    public ApiSettings(String bind, int port, Optional<ApiTls.Material> tls, List<String> corsOrigins,
                       ApiLimits limits) {
        this(bind, port, tls, corsOrigins, limits, false, 0);
    }

    /** Settings without gRPC. */
    public ApiSettings(String bind, int port, Optional<ApiTls.Material> tls, List<String> corsOrigins,
                       ApiLimits limits, boolean graphqlIntrospection) {
        this(bind, port, tls, corsOrigins, limits, graphqlIntrospection, 0);
    }

    /**
     * Settings from {@code api} in {@code cloud.yml}.
     *
     * @param api the section
     * @param tls the key material ({@link ApiTls#prepare}) when {@code api.tls.enabled}, otherwise empty
     * @return the settings with {@link ApiLimits#defaults()}
     * @throws IllegalArgumentException if {@code api.tls.enabled} and {@code tls} disagree: with TLS
     *                                  on and no material the server would speak plain HTTP
     */
    public static ApiSettings of(ApiConfig api, Optional<ApiTls.Material> tls) {
        Objects.requireNonNull(tls, "tls");
        boolean enabled = api.tls() != null && api.tls().enabled();
        if (enabled && tls.isEmpty()) {
            throw new IllegalArgumentException("api.tls.enabled is set in cloud.yml, but no key material was prepared");
        }
        if (!enabled && tls.isPresent()) {
            throw new IllegalArgumentException("key material was given, but api.tls.enabled is off in cloud.yml");
        }
        return new ApiSettings(api.bind(), api.port(), tls, api.corsOrigins(), ApiLimits.defaults(),
                api.graphqlIntrospection(), api.grpcPort());
    }

    /**
     * A copy with other limits.
     *
     * @param value the limits
     * @return the copy
     */
    public ApiSettings withLimits(ApiLimits value) {
        return new ApiSettings(bind, port, tls, corsOrigins, value, graphqlIntrospection, grpcPort);
    }

    /**
     * A copy with GraphQL introspection switched.
     *
     * @param value whether introspection is answered
     * @return the copy
     */
    public ApiSettings withGraphqlIntrospection(boolean value) {
        return new ApiSettings(bind, port, tls, corsOrigins, limits, value, grpcPort);
    }

    /**
     * A copy with another gRPC port.
     *
     * @param value the port, 0 for off, -1 for any free port
     * @return the copy
     */
    public ApiSettings withGrpcPort(int value) {
        return new ApiSettings(bind, port, tls, corsOrigins, limits, graphqlIntrospection, value);
    }
}
