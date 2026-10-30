package net.vectolyn.cloud.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.NodeId;

/**
 * What one end of a cluster session answers: request handlers and push handlers by channel. Fixed
 * before the session starts; a request or push on a channel without a handler ends the session
 * with {@code PROTOCOL_ERROR} (every node of a cluster runs the same version, so both ends know the
 * same channels).
 */
public final class ClusterHandlers {

    /** No handlers at all. */
    public static final ClusterHandlers NONE = builder().build();

    /** Answers a request. Runs on the session's worker, one request at a time. */
    @FunctionalInterface
    public interface RequestHandler {

        /**
         * Handles a request.
         *
         * @param peer    the node at the other end
         * @param payload the request's payload, from the network
         * @return the answer's payload, at most 64 KiB
         * @throws ClusterRpcException to answer with its code
         */
        byte[] handle(NodeId peer, byte[] payload) throws ClusterRpcException;
    }

    /** Takes a push. Runs on the session's worker, in order with the requests. */
    @FunctionalInterface
    public interface PushHandler {

        /**
         * Handles a push.
         *
         * @param peer    the node at the other end
         * @param payload the push's payload, from the network
         * @throws ClusterRpcException if the payload is not valid; it is logged
         */
        void handle(NodeId peer, byte[] payload) throws ClusterRpcException;
    }

    private final Map<ChannelName, RequestHandler> requests;
    private final Map<ChannelName, PushHandler> pushes;

    private ClusterHandlers(Map<ChannelName, RequestHandler> requests, Map<ChannelName, PushHandler> pushes) {
        this.requests = Map.copyOf(requests);
        this.pushes = Map.copyOf(pushes);
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The handler of requests on {@code channel}. */
    Optional<RequestHandler> request(ChannelName channel) {
        return Optional.ofNullable(requests.get(channel));
    }

    /** The handler of pushes on {@code channel}. */
    Optional<PushHandler> push(ChannelName channel) {
        return Optional.ofNullable(pushes.get(channel));
    }

    /** Collects the handlers. */
    public static final class Builder {

        private final Map<ChannelName, RequestHandler> requests = new HashMap<>();
        private final Map<ChannelName, PushHandler> pushes = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a request handler.
         *
         * @throws IllegalArgumentException if the channel has one already or is a handshake channel
         */
        public Builder request(ChannelName channel, RequestHandler handler) {
            check(channel);
            if (requests.putIfAbsent(channel, Objects.requireNonNull(handler, "handler")) != null) {
                throw new IllegalArgumentException("two request handlers for " + channel);
            }
            return this;
        }

        /**
         * Adds a push handler.
         *
         * @throws IllegalArgumentException if the channel has one already or is a handshake channel
         */
        public Builder push(ChannelName channel, PushHandler handler) {
            check(channel);
            if (pushes.putIfAbsent(channel, Objects.requireNonNull(handler, "handler")) != null) {
                throw new IllegalArgumentException("two push handlers for " + channel);
            }
            return this;
        }

        private static void check(ChannelName channel) {
            Objects.requireNonNull(channel, "channel");
            if (channel.equals(ClusterMessages.HELLO) || channel.equals(ClusterMessages.JOIN)) {
                throw new IllegalArgumentException(channel + " belongs to the handshake");
            }
        }

        /** The handlers, fixed from here on. */
        public ClusterHandlers build() {
            return new ClusterHandlers(requests, pushes);
        }
    }
}
