package net.vectolyn.cloud.cluster;

import java.util.concurrent.atomic.AtomicReference;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.message.Envelope;

/**
 * Two request layers wired to each other in memory, as a head and a member see a session. A frame
 * that the receiving side refuses (it would end the session with {@code PROTOCOL_ERROR}) is kept
 * in {@link #violation}.
 */
final class SessionPair implements AutoCloseable {

    final ClusterSession member;
    final ClusterSession head;
    final AtomicReference<Envelope> violation = new AtomicReference<>();

    SessionPair(ClusterHandlers memberHandlers, ClusterHandlers headHandlers) {
        ClusterSession[] sides = new ClusterSession[2];
        sides[0] = new ClusterSession(new NodeId("node-1"), memberHandlers, e -> deliver(sides[1], e));
        sides[1] = new ClusterSession(new NodeId("node-2"), headHandlers, e -> deliver(sides[0], e));
        member = sides[0];
        head = sides[1];
    }

    private boolean deliver(ClusterSession to, Envelope envelope) {
        if (!to.receive(envelope)) {
            violation.compareAndSet(null, envelope);
        }
        return true;
    }

    @Override
    public void close() {
        member.close();
        head.close();
    }
}
