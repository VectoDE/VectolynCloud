// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import java.util.Objects;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.api.backend.NodeStatus;
import net.vectolyn.cloud.api.backend.ServiceView;
import org.jspecify.annotations.Nullable;

/**
 * A live event for the WebSocket {@code /api/v1/events}. cloud-node hands them to
 * {@code ApiServer.publish}: state changes and output lines from its {@code ServiceListener},
 * player events from the cloud channel (the bridges, PR 13).
 *
 * <p>Each type needs a scope ({@link #scopeOf}): service states, output lines and players
 * {@code services:read}, metrics {@code node:read}, the node log {@code admin}.
 *
 * <p>On the wire every event is one JSON text frame {@code {"type": ..., "time": ..., "data": {...}}}.
 */
public sealed interface ApiEvent {

    /** Every event type and the scope a stream needs to receive it. */
    Map<String, Scope> TYPES = Map.of(
            "service-state", Scope.SERVICES_READ,
            "service-output", Scope.SERVICES_READ,
            "player", Scope.SERVICES_READ,
            "metrics", Scope.NODE_READ,
            "node-log", Scope.ADMIN);

    /**
     * The scope a stream needs for an event type.
     *
     * @param type the type name
     * @return the scope, or null for an unknown type
     */
    static @Nullable Scope scopeOf(String type) {
        return TYPES.get(type);
    }

    /** Type name on the wire, also the value of the {@code types} filter. */
    String type();

    /**
     * The service the event belongs to, for the {@code services} filter.
     *
     * @return the service id, or null if the event belongs to none
     */
    @Nullable String service();

    /**
     * A service changed its state.
     *
     * @param serviceView the service after the change
     * @param previous    the state before, or null when the service was just created
     */
    @JsonPropertyOrder({"service", "previous"})
    record ServiceState(@JsonProperty("service") ServiceView serviceView, @Nullable String previous) implements ApiEvent {

        /** Checks the service. */
        public ServiceState {
            Objects.requireNonNull(serviceView, "serviceView");
        }

        @Override
        public String type() {
            return "service-state";
        }

        @Override
        public String service() {
            return serviceView.id();
        }
    }

    /**
     * A service printed a line. The node passes only lines that went through its log filter; the
     * API redacts them once more before sending.
     *
     * @param serviceId the service
     * @param line      one line without its line break
     */
    @JsonPropertyOrder({"service", "line"})
    record ServiceOutput(@JsonProperty("service") String serviceId, String line) implements ApiEvent {

        /** Checks the values. */
        public ServiceOutput {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(line, "line");
        }

        @Override
        public String type() {
            return "service-output";
        }

        @Override
        public String service() {
            return serviceId;
        }
    }

    /**
     * A player joined the network, switched servers or left.
     *
     * @param kind       {@code join}, {@code switch} or {@code leave}
     * @param playerId   the player's UUID
     * @param playerName the player's name
     * @param serviceId  the service the player is on now (for {@code leave}: was on), or null
     */
    @JsonPropertyOrder({"kind", "player-id", "player-name", "service"})
    record Player(String kind, String playerId, String playerName, @JsonProperty("service") String serviceId) implements ApiEvent {

        /** Checks the values. */
        public Player {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(playerId, "playerId");
        }

        @Override
        public String type() {
            return "player";
        }

        @Override
        public String service() {
            return serviceId;
        }
    }

    /**
     * Figures of the node, sent every few seconds while a stream wants them.
     *
     * @param status the node status, as {@code GET /api/v1/node} shows it
     */
    @JsonPropertyOrder({"node"})
    record Metrics(@JsonProperty("node") NodeStatus status) implements ApiEvent {

        /** Checks the status. */
        public Metrics {
            Objects.requireNonNull(status, "status");
        }

        @Override
        public String type() {
            return "metrics";
        }

        @Override
        public @Nullable String service() {
            return null;
        }
    }

    /**
     * A line of the node's own log ({@code logs/cloud.log}), level INFO and above. Redacted like the
     * log file before it is sent.
     *
     * @param level   {@code info}, {@code warning} or {@code error}
     * @param logger  the name of the logger, for example {@code net.vectolyn.cloud.node.CloudNode}
     * @param message the message, without a stack trace; at most 2000 characters
     */
    @JsonPropertyOrder({"level", "logger", "message"})
    record NodeLog(String level, String logger, String message) implements ApiEvent {

        /** Checks the values. */
        public NodeLog {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(logger, "logger");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String type() {
            return "node-log";
        }

        @Override
        public @Nullable String service() {
            return null;
        }
    }
}
