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
 * in {@code VECTORA_SERVICE_TOKEN}; it calls {@link #remove} with that token when the process has
 * ended.
 *
 * <p>Each token is valid for one accepted connection. {@link #admit} uses it up and hands out a
 * fresh ticket, which the cloud sends over the accepted connection only ({@code SessionTicket});
 * the service's next connection needs that ticket. So the token in the environment of the server
 * process, which every plugin and on some systems other local processes can read, is worthless
 * once the bridge has connected, and a ticket works once as well. Cluster nodes
 * ({@link net.vectora.protocol.NodeId}) are refused.
 */
public final class ServiceTokens implements ServiceAuthenticator {

    /** Random bytes per token: 32 bytes, 43 characters in URL-safe Base64. */
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentMap<ServiceId, Credential> tokens = new ConcurrentHashMap<>();
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
        tokens.put(Objects.requireNonNull(service, "service"), new Credential(token, token));
        return token;
    }

    /** Stores a token chosen elsewhere for {@code service}, replacing an older one. */
    public void register(ServiceId service, AuthToken token) {
        Objects.requireNonNull(token, "token");
        tokens.put(Objects.requireNonNull(service, "service"), new Credential(token, token));
    }

    /**
     * Forgets the token of {@code service}, together with any ticket handed out for it; later
     * connections are refused. Nothing happens if the service has been issued another token by
     * now: the exit of an old process cannot revoke the token a newer start of the same service
     * was given.
     *
     * @param service the service
     * @param token   the token that was issued for the start that has ended (not a ticket)
     * @return whether the token was the current one and is removed now
     */
    public boolean remove(ServiceId service, AuthToken token) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(token, "token");
        boolean[] removed = {false};
        tokens.computeIfPresent(service, (id, credential) -> {
            if (credential.issued().matches(token)) {
                removed[0] = true;
                return null;
            }
            return credential;
        });
        return removed[0];
    }

    /** Returns the services that currently have a token. */
    public Set<ServiceId> services() {
        return Set.copyOf(tokens.keySet());
    }

    @Override
    public AuthResult.Outcome authenticate(PeerId peer, AuthToken token) {
        Objects.requireNonNull(token, "token");
        Credential credential = peer instanceof ServiceId service ? tokens.get(service) : null;
        boolean matches = (credential == null ? decoy : credential.current()).matches(token);
        if (credential == null) {
            return AuthResult.Outcome.UNKNOWN_PEER;
        }
        return matches ? AuthResult.Outcome.ACCEPTED : AuthResult.Outcome.INVALID_TOKEN;
    }

    @Override
    public Admission admit(PeerId peer, AuthToken token) {
        Objects.requireNonNull(token, "token");
        ServiceId service = peer instanceof ServiceId id ? id : null;
        Credential credential = service == null ? null : tokens.get(service);
        boolean matches = (credential == null ? decoy : credential.current()).matches(token);
        if (credential == null) {
            return Admission.refused(AuthResult.Outcome.UNKNOWN_PEER);
        }
        if (!matches) {
            return Admission.refused(AuthResult.Outcome.INVALID_TOKEN);
        }
        // Compare and swap: of two connections with the same token only one gets in, the other
        // finds the credential changed and is refused.
        AuthToken next = newToken(random);
        if (!tokens.replace(service, credential, new Credential(credential.issued(), next))) {
            return Admission.refused(AuthResult.Outcome.INVALID_TOKEN);
        }
        return Admission.accepted(next);
    }

    private static AuthToken newToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return new AuthToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * What the cloud knows about one start.
     *
     * @param issued  the token the process was started with; {@link #remove} matches against it
     * @param current the token the next connection has to present: {@code issued} until the
     *                first connection, then the newest ticket
     */
    private record Credential(AuthToken issued, AuthToken current) {
    }
}
