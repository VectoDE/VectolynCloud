package net.vectolyn.cloud.api.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;
import net.vectolyn.cloud.api.backend.ServiceView;

/**
 * A live event for the WebSocket {@code /api/v1/events}. cloud-node hands them to
 * {@code ApiServer.publish}: state changes and output lines from its {@code ServiceListener},
 * player events from the cloud channel (the bridges, PR 13).
 *
 * <p>On the wire every event is one JSON text frame {@code {"type": ..., "time": ..., "data": {...}}}.
 */
public sealed interface ApiEvent {

    /** Type name on the wire, also the value of the {@code types} filter. */
    String type();

    /**
     * The service the event belongs to, for the {@code services} filter.
     *
     * @return the service id, or null if the event belongs to none
     */
    String service();

    /**
     * A service changed its state.
     *
     * @param serviceView the service after the change
     * @param previous    the state before, or null when the service was just created
     */
    @JsonPropertyOrder({"service", "previous"})
    record ServiceState(@JsonProperty("service") ServiceView serviceView, String previous) implements ApiEvent {

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
}
