// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel;

import io.netty.channel.Channel;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.vectolyn.cloud.api.ApiServer;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.http.ApiException;
import net.vectolyn.cloud.api.http.ApiResponse;

/** What the panel needs of the node's API; {@link #of} adapts the running {@link ApiServer}. */
public interface NodeAccess {

    /**
     * Uses the one-time setup token.
     *
     * @param token the token the user typed
     * @param use   the work, given the admin name of the token
     * @param <T>   its result
     * @return the result, or empty for a wrong, used or expired token
     * @throws IOException if the work fails; the token stays valid
     */
    <T> Optional<T> useSetupToken(String token, ApiTokens.SetupUse<T> use) throws IOException;

    /**
     * Runs a REST route for a signed-in user.
     *
     * @param principal the user as a local token
     * @param method    HTTP method
     * @param target    path below {@code /api/v1}, with query
     * @param body      JSON body
     * @param ifMatch   {@code If-Match}, or null
     * @return the answer
     * @throws ApiException the error the API answers
     */
    ApiResponse call(ApiToken principal, String method, String target, byte[] body, String ifMatch) throws ApiException;

    /**
     * Adds the WebSocket of a signed-in user to the node's event streams.
     *
     * @param channel   the WebSocket, after its handshake
     * @param principal the user as a local token
     * @param types     event types asked for, empty for all the user may see
     * @param services  services to follow, empty for all
     * @return the event types the stream gets
     * @throws ApiException 400 for an unknown type, 403 for a missing scope, 503 for too many streams
     */
    Set<String> subscribe(Channel channel, ApiToken principal, Set<String> types, Set<String> services)
            throws ApiException;

    /**
     * Adapts a running API server.
     *
     * @param server the server
     * @return the access
     */
    static NodeAccess of(ApiServer server) {
        Objects.requireNonNull(server, "server");
        return new NodeAccess() {
            @Override
            public <T> Optional<T> useSetupToken(String token, ApiTokens.SetupUse<T> use) throws IOException {
                return server.useSetupToken(token, use);
            }

            @Override
            public ApiResponse call(ApiToken principal, String method, String target, byte[] body, String ifMatch)
                    throws ApiException {
                return server.call(principal, method, target, body, ifMatch);
            }

            @Override
            public Set<String> subscribe(Channel channel, ApiToken principal, Set<String> types, Set<String> services)
                    throws ApiException {
                return server.subscribe(channel, principal, types, services);
            }
        };
    }
}
