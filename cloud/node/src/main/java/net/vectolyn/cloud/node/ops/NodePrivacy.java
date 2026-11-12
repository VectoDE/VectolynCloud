// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.ops;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;
import net.vectolyn.cloud.network.RequestHandler;
import net.vectolyn.cloud.node.channel.NetworkPlayers;
import net.vectolyn.cloud.privacy.PrivacyRequests;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;

/**
 * The {@code /privacy} requests of the proxies ({@code docs/cloud/privacy.md}). A request is
 * {@code <player uuid> <request>}. It is answered only if it comes from a proxy and the player is
 * online through exactly that proxy; any other sender gets {@code error not-online}. This keeps a
 * server from reading or deleting the data of players it does not host.
 */
public final class NodePrivacy implements RequestHandler {

    /** The channel name of {@link PrivacyRequests#CHANNEL}. */
    public static final ChannelName CHANNEL = new ChannelName(PrivacyRequests.CHANNEL);

    private static final int UUID_LENGTH = 36;

    private final PrivacyRequests requests;
    private final Predicate<ServiceId> isProxy;
    private final Function<UUID, Optional<NetworkPlayers.Player>> players;
    private final Function<ServiceId, Optional<String>> modeOf;

    /**
     * Creates the handler.
     *
     * @param requests the request logic
     * @param isProxy  whether a service is a proxy
     * @param players  the player directory
     * @param modeOf   the game mode (group) of a backend service
     */
    public NodePrivacy(PrivacyRequests requests, Predicate<ServiceId> isProxy,
                       Function<UUID, Optional<NetworkPlayers.Player>> players,
                       Function<ServiceId, Optional<String>> modeOf) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.isProxy = Objects.requireNonNull(isProxy, "isProxy");
        this.players = Objects.requireNonNull(players, "players");
        this.modeOf = Objects.requireNonNull(modeOf, "modeOf");
    }

    @Override
    public CompletionStage<Response> handle(ServiceId service, Request request) {
        return CompletableFuture.completedFuture(new Response(Response.Status.OK, answer(service, request.payload())));
    }

    private byte[] answer(ServiceId service, byte[] payload) {
        if (payload.length <= UUID_LENGTH + 1 || payload[UUID_LENGTH] != ' ') {
            return text("error bad-request");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(new String(payload, 0, UUID_LENGTH, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return text("error bad-request");
        }
        Optional<NetworkPlayers.Player> player = isProxy.test(service) ? players.apply(uuid) : Optional.empty();
        if (player.isEmpty() || !player.get().proxy().equals(service)) {
            return text("error not-online");
        }
        String mode = modeOf.apply(player.get().service()).orElse(null);
        return requests.handle(Arrays.copyOfRange(payload, UUID_LENGTH + 1, payload.length), uuid,
                player.get().name(), mode);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
