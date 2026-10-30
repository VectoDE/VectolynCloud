package net.vectolyn.cloud.node.channel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.vectolyn.protocol.ServiceId;

/**
 * Where the players of a backend that is emptied go: one by one to the other backend of the group
 * with the lowest load in percent at that moment, counting the players already sent there. Equal
 * load: fewer players, then the name. A pure function, the same rule the proxy uses for joins.
 */
public final class TransferPlanner {

    private TransferPlanner() {
    }

    /**
     * A backend players may be moved to.
     *
     * @param name    its name on the proxy
     * @param players players on it now
     * @param slots   players it is meant for, 1 or more
     */
    public record Target(ServiceId name, int players, int slots) {

        /** Checks the values. */
        public Target {
            Objects.requireNonNull(name, "name");
            if (slots < 1 || players < 0) {
                throw new IllegalArgumentException("slots must be 1 or more and players 0 or more");
            }
        }
    }

    /**
     * Assigns every player to a target.
     *
     * @param players the players to move, in this order
     * @param targets the backends that may take them
     * @return player to target name, in the order of {@code players}; empty without targets
     */
    public static Map<UUID, ServiceId> assign(List<UUID> players, List<Target> targets) {
        Map<UUID, ServiceId> result = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            return result;
        }
        List<int[]> counts = new ArrayList<>();
        for (Target target : targets) {
            counts.add(new int[] {target.players()});
        }
        Comparator<Integer> byLoad = Comparator
                .<Integer>comparingDouble(i -> (double) counts.get(i)[0] / targets.get(i).slots())
                .thenComparingInt(i -> counts.get(i)[0])
                .thenComparing(i -> targets.get(i).name().value());
        for (UUID player : players) {
            int best = 0;
            for (int i = 1; i < targets.size(); i++) {
                if (byLoad.compare(i, best) < 0) {
                    best = i;
                }
            }
            counts.get(best)[0]++;
            result.put(player, targets.get(best).name());
        }
        return result;
    }
}
