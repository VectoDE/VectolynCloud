package net.vectora.cloud.config.model;

/**
 * How many services of a task run. A static task keeps {@code minServices} running; a dynamic
 * task scales between {@code minServices} and {@code maxServices} by player count.
 *
 * @param minServices       services that always run, 0 or more
 * @param maxServices       upper limit, at least 1 and at least {@code minServices}
 * @param playersPerService players one service is meant for
 * @param scaleUpAt         start another service when the fullest one reaches this share of
 *                          {@code playersPerService}, in percent (1 to 100)
 * @param scaleDownAfter    stop a surplus empty service after it has been empty this long
 * @param cooldown          minimum time between two scaling decisions of the task
 */
public record ScalingConfig(int minServices, int maxServices, int playersPerService, int scaleUpAt,
                            TimeSpan scaleDownAfter, TimeSpan cooldown) {

    /** One service, up to 50 players, scale at 80 %, stop after 5 minutes, 1 minute cooldown. */
    public static ScalingConfig single() {
        return new ScalingConfig(1, 1, 50, 80, TimeSpan.ofSeconds(300), TimeSpan.ofSeconds(60));
    }
}
