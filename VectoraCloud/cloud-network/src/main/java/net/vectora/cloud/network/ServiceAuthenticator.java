package net.vectora.cloud.network;

import net.vectora.protocol.AuthToken;
import net.vectora.protocol.PeerId;
import net.vectora.protocol.message.AuthResult;

/**
 * Decides whether a peer may open a connection. The node implements it with the service tokens
 * it creates per start; {@link ServiceTokens} is the ready-made implementation.
 *
 * <p>Called on a network thread for every {@code Hello}; must not block. Implementations compare
 * tokens with {@link AuthToken#matches} (constant time) and never log them.
 */
@FunctionalInterface
public interface ServiceAuthenticator {

    /**
     * Checks a peer.
     *
     * @return {@link AuthResult.Outcome#ACCEPTED} to let it in, otherwise the reason it is refused
     */
    AuthResult.Outcome authenticate(PeerId peer, AuthToken token);
}
