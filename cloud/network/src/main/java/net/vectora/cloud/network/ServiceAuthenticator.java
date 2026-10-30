package net.vectora.cloud.network;

import java.util.Objects;
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
public interface ServiceAuthenticator {

    /**
     * Checks a peer without using its token up, for diagnostics and tests.
     *
     * @return {@link AuthResult.Outcome#ACCEPTED} if {@link #admit} would let it in now,
     *     otherwise the reason it would be refused
     */
    AuthResult.Outcome authenticate(PeerId peer, AuthToken token);

    /**
     * Checks a peer for a new connection and, if it is accepted, uses its token up in the same
     * step: the token is refused from then on, and the peer's next connection needs the ticket in
     * the returned admission. Two connections with the same token never both get in.
     *
     * @return the outcome, with the next ticket when it is {@link AuthResult.Outcome#ACCEPTED}
     */
    Admission admit(PeerId peer, AuthToken token);

    /**
     * Result of {@link #admit}.
     *
     * @param outcome result of the check
     * @param ticket the token for the peer's next connection; set exactly when the outcome is
     *     {@link AuthResult.Outcome#ACCEPTED}
     */
    record Admission(AuthResult.Outcome outcome, AuthToken ticket) {

        /** Checks that a ticket comes with an acceptance and only then. */
        public Admission {
            Objects.requireNonNull(outcome, "outcome");
            if ((outcome == AuthResult.Outcome.ACCEPTED) != (ticket != null)) {
                throw new IllegalArgumentException("a ticket comes with an accepted admission and only then");
            }
        }

        /** A refusal with {@code outcome}. */
        public static Admission refused(AuthResult.Outcome outcome) {
            return new Admission(outcome, null);
        }

        /** An acceptance with the ticket for the next connection. */
        public static Admission accepted(AuthToken ticket) {
            return new Admission(AuthResult.Outcome.ACCEPTED, Objects.requireNonNull(ticket, "ticket"));
        }
    }
}
