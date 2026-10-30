package net.vectolyn.cloud.bridge.paper;

import java.util.OptionalInt;

/**
 * Where a player who joins inside blocks is moved to.
 *
 * <p>Paper 26.3 no longer keeps the spawn chunks loaded. A player who joins for the first time is
 * then placed with the height of a column that is not generated yet: at the world's bottom
 * ({@code y = -63} in the overworld), inside stone, and the server answers every move with
 * "moved wrongly". The bridge checks the block at the feet and the head once the player is in the
 * world and, if either is not passable, moves the player on top of the highest block of the
 * column. A player standing in a free spot is never moved.
 */
final class SafeSpawn {

    private SafeSpawn() {
    }

    /** The blocks of the column the player stands in. */
    interface Column {

        /**
         * Whether a player can be inside the block at {@code y}.
         *
         * @param y the block height
         * @return whether it is passable (air, grass, water and the like)
         */
        boolean passable(int y);

        /**
         * The height of the highest block that is not air, as Bukkit's {@code getHighestBlockYAt}.
         *
         * @return the height; the world's bottom if the column is empty
         */
        int highest();

        /**
         * The highest height a player can stand at in this world.
         *
         * @return the build limit minus two
         */
        int top();
    }

    /**
     * The height to move the player to.
     *
     * @param column the column at the player's block position
     * @param feet   the block height of the player's feet
     * @return empty if the player is free (feet and head passable) or no free spot exists; else
     *     the block height above the column's highest block
     */
    static OptionalInt rescue(Column column, int feet) {
        if (column.passable(feet) && column.passable(feet + 1)) {
            return OptionalInt.empty();
        }
        int target = column.highest() + 1;
        if (target > column.top() || !column.passable(target) || !column.passable(target + 1)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(target);
    }
}
