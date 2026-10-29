package net.vectora.cloud.bridge.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SafeSpawnTest {

    /** Solid from the bottom ({@code -64}) up to {@code ground}, air above, build limit 320. */
    private static SafeSpawn.Column ground(int ground) {
        return new SafeSpawn.Column() {
            @Override
            public boolean passable(int y) {
                return y > ground;
            }

            @Override
            public int highest() {
                return ground;
            }

            @Override
            public int top() {
                return 318;
            }
        };
    }

    @Test
    void aPlayerInsideStoneAtTheBottomIsMovedOnTop() {
        // The first join on Paper 26.3: x and z of the spawn, y at the world's bottom.
        assertEquals(OptionalInt.of(86), SafeSpawn.rescue(ground(85), -63));
    }

    @Test
    void aPlayerWithOnlyTheHeadInABlockIsMovedToo() {
        assertEquals(OptionalInt.of(86), SafeSpawn.rescue(ground(85), 84));
    }

    @Test
    void aPlayerStandingFreeStaysWhereHeIs() {
        assertEquals(OptionalInt.empty(), SafeSpawn.rescue(ground(85), 86));
        // Counter-check: also in a cave below the surface, as long as feet and head are free.
        SafeSpawn.Column cave = new SafeSpawn.Column() {
            @Override
            public boolean passable(int y) {
                return y == 40 || y == 41 || y > 85;
            }

            @Override
            public int highest() {
                return 85;
            }

            @Override
            public int top() {
                return 318;
            }
        };
        assertEquals(OptionalInt.empty(), SafeSpawn.rescue(cave, 40));
    }

    @Test
    void noFreeSpotAboveMeansNoMove() {
        assertEquals(OptionalInt.empty(), SafeSpawn.rescue(ground(318), 10));
    }
}
