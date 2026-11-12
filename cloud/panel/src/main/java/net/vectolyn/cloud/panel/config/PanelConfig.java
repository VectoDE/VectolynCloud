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
 * @param oidc          sign-in with an OpenID Connect provider; optional, needs {@code public-url}
 */
@JsonPropertyOrder({"schema-version", "enabled", "bind", "port", "public-url", "oidc"})
public record PanelConfig(@JsonProperty(required = true) int schemaVersion, Boolean enabled, String bind,
                          Integer port, @Nullable String publicUrl, @Nullable Oidc oidc) {

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
        return new PanelConfig(SCHEMA_VERSION, Boolean.FALSE, "127.0.0.1", DEFAULT_PORT, null, null);
    }

    /**
     * Shorter form without OpenID Connect.
     *
     * @param schemaVersion format version
     * @param enabled       whether the panel runs
     * @param bind          address
     * @param port          port
     * @param publicUrl     public address, or null
     */
    public PanelConfig(int schemaVersion, Boolean enabled, String bind, Integer port, @Nullable String publicUrl) {
        this(schemaVersion, enabled, bind, port, publicUrl, null);
    }

    /**
     * {@code oidc}: sign-in with an OpenID Connect provider (authorization code flow with PKCE).
     * The client secret is not in this file: it is {@code VECTOLYN_PANEL_OIDC_SECRET} in
     * {@code secrets/vectolyn.env}. A provider account signs in only after a panel user linked it.
     *
     * @param issuer   the provider's issuer URL, {@code https://...} (its discovery document must
     *                 name the same issuer)
     * @param clientId the client id registered at the provider
     * @param name     the label of the sign-in button (default {@code Single sign-on})
     * @param scopes   scopes to ask for; {@code openid} is always added (default {@code openid profile email})
     */
    @JsonPropertyOrder({"issuer", "client-id", "name", "scopes"})
    public record Oidc(@JsonProperty(required = true) String issuer, @JsonProperty(required = true) String clientId,
                       String name, java.util.List<String> scopes) {

        /** Fills in the defaults. */
        public Oidc {
            name = name == null ? "Single sign-on" : name;
            scopes = scopes == null ? java.util.List.of("openid", "profile", "email") : java.util.List.copyOf(scopes);
        }
    }
}
