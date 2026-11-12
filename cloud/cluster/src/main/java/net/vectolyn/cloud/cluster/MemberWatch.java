// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.vectolyn.protocol.NodeId;

/**
 * The head's view of which members are lost, without a clock of its own: a member that had a
 * session since the head started (it reported a status) is <b>lost</b> once it has been without a
 * session for {@code grace}. Its session itself ends after 30 s without a frame
 * ({@code docs/cloud/cluster.md#limits-on-the-cluster-port}), or at once when the connection
 * breaks. A lost member that connects again is <b>back</b>. A member that never had a session
 * since the head started is never lost: the head does not know what it runs.
 *
 * <p>Not thread-safe; the head calls it from one timer thread.
 */
final class MemberWatch {

    /** What changed in one {@link #update}. */
    record Change(List<NodeId> lost, List<NodeId> back) {

        Change {
            lost = List.copyOf(lost);
            back = List.copyOf(back);
        }

        boolean isEmpty() {
            return lost.isEmpty() && back.isEmpty();
        }
    }

    private final Duration grace;
    private final Map<NodeId, Instant> missingSince = new HashMap<>();
    private final Set<NodeId> lost = new LinkedHashSet<>();

    MemberWatch(Duration grace) {
        if (grace.isNegative()) {
            throw new IllegalArgumentException("negative grace");
        }
        this.grace = grace;
    }

    /**
     * Looks at the members once.
     *
     * @param now       the time
     * @param seen      members that had a session since the head started
     * @param connected members with a session now
     * @return members lost or back since the last call
     */
    Change update(Instant now, Set<NodeId> seen, Set<NodeId> connected) {
        Objects.requireNonNull(now, "now");
        List<NodeId> newlyLost = new ArrayList<>();
        List<NodeId> back = new ArrayList<>();
        for (NodeId node : seen) {
            if (connected.contains(node)) {
                missingSince.remove(node);
                if (lost.remove(node)) {
                    back.add(node);
                }
                continue;
            }
            if (lost.contains(node)) {
                continue;
            }
            Instant since = missingSince.computeIfAbsent(node, n -> now);
            if (!now.isBefore(since.plus(grace))) {
                missingSince.remove(node);
                lost.add(node);
                newlyLost.add(node);
            }
        }
        // A member that left the cluster (removed) is forgotten.
        missingSince.keySet().retainAll(seen);
        lost.retainAll(seen);
        return new Change(newlyLost, back);
    }

    /** The members lost now. */
    Set<NodeId> lost() {
        return Set.copyOf(lost);
    }
}
