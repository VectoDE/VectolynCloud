package net.vectolyn.cloud.node.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.vectolyn.cloud.node.channel.TransferPlanner.Target;
import net.vectolyn.protocol.ServiceId;
import org.junit.jupiter.api.Test;

/** Where the players of an emptied backend go. */
class TransferPlannerTest {

    private static final ServiceId A = new ServiceId("lobby-1");
    private static final ServiceId B = new ServiceId("lobby-2");
    private static final ServiceId C = new ServiceId("lobby-3");

    private static List<UUID> players(int count) {
        List<UUID> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new UUID(0, i + 1L));
        }
        return list;
    }

    @Test
    void eachPlayerGoesToTheTargetWithTheLowestLoadCountingThoseAlreadySent() {
        // lobby-1 10 of 50 (20 %), lobby-2 2 of 10 (20 %), lobby-3 1 of 20 (5 %).
        List<UUID> moving = players(4);
        Map<UUID, ServiceId> plan = TransferPlanner.assign(moving,
                List.of(new Target(A, 10, 50), new Target(B, 2, 10), new Target(C, 1, 20)));
        // 1st: lobby-3 (5 %) -> 2 of 20 (10 %); 2nd: lobby-3 (10 %) -> 15 %; 3rd: lobby-3 (15 %) -> 20 %;
        // 4th: all at 20 %, lobby-2 has the fewest players (2 against 4 and 10).
        assertEquals(List.of(C, C, C, B), moving.stream().map(plan::get).toList());

        // Counter-check: with lobby-3 fuller, the next player goes to the emptier one by percent, not by count.
        Map<UUID, ServiceId> other = TransferPlanner.assign(players(1),
                List.of(new Target(A, 10, 50), new Target(B, 3, 10)));
        assertEquals(A, other.values().iterator().next(), "20 % beats 30 % although lobby-1 has more players");
    }

    @Test
    void equalLoadGoesToFewerPlayersThenToTheName() {
        assertEquals(B, TransferPlanner.assign(players(1), List.of(new Target(A, 10, 50), new Target(B, 2, 10)))
                .values().iterator().next(), "both 20 %, lobby-2 has fewer players");
        assertEquals(A, TransferPlanner.assign(players(1), List.of(new Target(B, 2, 10), new Target(A, 2, 10)))
                .values().iterator().next(), "same load and count: by name");
    }

    @Test
    void playersAreSpreadAndKeepTheirOrder() {
        List<UUID> moving = players(4);
        Map<UUID, ServiceId> plan = TransferPlanner.assign(moving, List.of(new Target(A, 0, 10), new Target(B, 0, 10)));
        assertEquals(moving, List.copyOf(plan.keySet()));
        assertEquals(List.of(A, B, A, B), List.copyOf(plan.values()));
    }

    @Test
    void withoutTargetsNobodyIsAssignedAndATargetNeedsASlot() {
        assertEquals(Map.of(), TransferPlanner.assign(players(3), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Target(A, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Target(A, -1, 10));
    }
}
