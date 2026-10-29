package net.vectora.cloud.network;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.vectora.protocol.AuthToken;
import net.vectora.protocol.PeerId;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.AuthResult;

/**
 * The tokens of the services this node has started, one per service and start. Thread-safe.
 *
 * <p>The node calls {@link #issue} before it starts a service and hands the token to the process
 * in {@code VECTORA_SERVICE_TOKEN}; it calls {@link #remove} with that token when the process has ended. Cluster
 * nodes ({@link net.vectora.protocol.NodeId}) are refused until the cluster exists (stage 2,
 * PR 20).
 */
public final class ServiceTokens implements ServiceAuthenticator {

    /** Random bytes per token: 32 bytes, 43 characters in URL-safe Base64. */
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentMap<ServiceId, AuthToken> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Compared against for unknown ids, so an unknown id takes as long as a known one. */
    private final AuthToken decoy;

    /** Creates an empty registry. */
    public ServiceTokens() {
        decoy = newToken(random);
    }

    /** Creates a fresh random token for {@code service}, replacing an older one, and returns it. */
    public AuthToken issue(ServiceId service) {
        AuthToken token = newToken(random);
        tokens.put(Objects.requireNonNull(service, "service"), token);
        return token;
    }

    /** Stores a token chosen elsewhere for {@code service}, replacing an older one. */
    public void register(ServiceId service, AuthToken token) {
        tokens.put(Objects.requireNonNull(service, "service"), Objects.requireNonNull(token, "token"));
    }

    /**
     * Forgets {@code token} of {@code service}; later connections with it are refused. Nothing
     * happens if the service has another token by now: the exit of an old process cannot revoke the
     * token a newer start of the same service was given.
     *
     * @param service the service
     * @param token   the token that was issued for the start that has ended
     * @return whether the token was the current one and is removed now
     */
    public boolean remove(ServiceId service, AuthToken token) {
        return tokens.remove(Objects.requireNonNull(service, "service"), Objects.requireNonNull(token, "token"));
    }

    /** Returns the services that currently have a token. */
    public Set<ServiceId> services() {
        return Set.copyOf(tokens.keySet());
    }

    @Override
    public AuthResult.Outcome authenticate(PeerId peer, AuthToken token) {
        Objects.requireNonNull(token, "token");
        AuthToken expected = peer instanceof ServiceId service ? tokens.get(service) : null;
        boolean matches = (expected == null ? decoy : expected).matches(token);
        if (expected == null) {
            return AuthResult.Outcome.UNKNOWN_PEER;
        }
        return matches ? AuthResult.Outcome.ACCEPTED : AuthResult.Outcome.INVALID_TOKEN;
    }

    private static AuthToken newToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return new AuthToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
