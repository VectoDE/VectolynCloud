// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/**
 * {@code config/panel.yml} in the cloud home. Every field except {@code schema-version} is
 * optional. Without the file the panel is off. Checked by {@link PanelConfigLoader}.
 *
 * @param schemaVersion format version, {@value #SCHEMA_VERSION}
 * @param enabled       whether the node starts the panel server (default false); it also needs the
 *                      API ({@code api.enabled} in {@code cloud.yml}), which it talks to in-process
 * @param bind          address to listen on (default {@code 127.0.0.1}); any other address than
 *                      loopback needs the TLS of the API ({@code api.tls.enabled})
 * @param port          TCP port, 1 to 65535 (default 8090)
 * @param publicUrl     the address the browser uses, {@code https://host[:port]} or, on loopback,
 *                      {@code http://localhost:port}; optional. Requests whose {@code Origin}
 *                      differs are refused, passkeys are bound to its host, and the sign-in with
 *                      an identity provider returns to it. Without it the panel accepts only its
 *                      own bind address and {@code localhost}.
 */
@JsonPropertyOrder({"schema-version", "enabled", "bind", "port", "public-url"})
public record PanelConfig(@JsonProperty(required = true) int schemaVersion, Boolean enabled, String bind,
                          Integer port, @Nullable String publicUrl) {

    /** Current format version. */
    public static final int SCHEMA_VERSION = 1;

    /** Default port. */
    public static final int DEFAULT_PORT = 8090;

    /** Fills in the defaults. */
    public PanelConfig {
        enabled = enabled == null ? Boolean.FALSE : enabled;
        bind = bind == null ? "127.0.0.1" : bind;
        port = port == null ? DEFAULT_PORT : port;
    }

    /**
     * Configuration without a file: the panel is off.
     *
     * @return the defaults
     */
    public static PanelConfig defaults() {
        return new PanelConfig(SCHEMA_VERSION, Boolean.FALSE, "127.0.0.1", DEFAULT_PORT, null);
    }
}
