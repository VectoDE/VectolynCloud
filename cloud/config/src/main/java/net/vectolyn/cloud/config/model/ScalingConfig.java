package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * How many services of a task run, and which of them are static. The rules are in
 * {@code docs/cloud/scaling.md}.
 *
 * <p>A task has {@code staticServices} static services, which keep their folder, always count and
 * are never stopped by the scaler, and above them dynamic overflow services up to
 * {@code maxServices}, created from the template on each start and deleted after the stop. The
 * pure forms are {@code staticServices = minServices = maxServices} (only static services, never
 * scaled) and {@code staticServices = 0} (only dynamic ones). Which service is static is decided
 * when it is created: as long as the task has fewer static services than {@code staticServices},
 * a new one is static.
 *
 * @param staticServices    static services of the task, 0 to {@code minServices}; required in the file,
 *                          because a missing number would silently make every service dynamic
 * @param minServices       services that always run, {@code staticServices} or more
 * @param maxServices       upper limit, at least 1 and at least {@code minServices}
 * @param playersPerService players one service is meant for
 * @param scaleUpAt         share of the places, in percent (1 to 100), at which another service
 *                          starts
 * @param scaleDownBelow    share of the places, in percent (0 to 99, below {@code scaleUpAt}), below
 *                          which a dynamic service may go; null (a file without the field) becomes
 *                          {@link #DEFAULT_SCALE_DOWN_BELOW}
 * @param scaleDownAfter    stop a surplus empty service after it has been empty this long
 * @param cooldown          minimum time between two scaling decisions of the task
 */
@JsonPropertyOrder({"static-services", "min-services", "max-services", "players-per-service", "scale-up-at",
        "scale-down-below", "scale-down-after", "cooldown"})
public record ScalingConfig(@JsonProperty(required = true) int staticServices, int minServices, int maxServices, int playersPerService, int scaleUpAt,
                            Integer scaleDownBelow, TimeSpan scaleDownAfter, TimeSpan cooldown) {

    /** {@code scale-up-at} of new tasks, in percent. */
    public static final int DEFAULT_SCALE_UP_AT = 75;
    /** {@code scale-down-below} of new tasks and of files without the field, in percent. */
    public static final int DEFAULT_SCALE_DOWN_BELOW = 50;

    /** A missing {@code scale-down-below} becomes {@link #DEFAULT_SCALE_DOWN_BELOW}. */
    public ScalingConfig {
        scaleDownBelow = scaleDownBelow == null ? DEFAULT_SCALE_DOWN_BELOW : scaleDownBelow;
    }

    /**
     * One static service, never scaled: up to 50 players, scale at 75 %, below 50 %, stop after 5
     * minutes, 1 minute cooldown.
     */
    public static ScalingConfig single() {
        return counts(1, 1, 1);
    }

    /**
     * The defaults of {@link #single()} with other service counts.
     *
     * @param staticServices static services
     * @param minServices    services that always run
     * @param maxServices    upper limit
     * @return the scaling
     */
    public static ScalingConfig counts(int staticServices, int minServices, int maxServices) {
        return new ScalingConfig(staticServices, minServices, maxServices, 50, DEFAULT_SCALE_UP_AT, DEFAULT_SCALE_DOWN_BELOW,
                TimeSpan.ofSeconds(300), TimeSpan.ofSeconds(60));
    }

    /**
     * Whether the task may have dynamic services at all: {@code max-services} leaves room above the
     * static ones.
     *
     * @return true if {@code maxServices > staticServices}
     */
    public boolean allowsDynamic() {
        return maxServices > staticServices;
    }

    /** A copy with other service counts. */
    public ScalingConfig withCounts(int newStatic, int newMin, int newMax) {
        return new ScalingConfig(newStatic, newMin, newMax, playersPerService, scaleUpAt, scaleDownBelow, scaleDownAfter,
                cooldown);
    }

    /** A copy with another {@code static-services}. */
    public ScalingConfig withStaticServices(int value) {
        return withCounts(value, minServices, maxServices);
    }

    /** A copy with another {@code min-services}. */
    public ScalingConfig withMinServices(int value) {
        return withCounts(staticServices, value, maxServices);
    }

    /** A copy with another {@code max-services}. */
    public ScalingConfig withMaxServices(int value) {
        return withCounts(staticServices, minServices, value);
    }

    /** A copy with another {@code players-per-service}. */
    public ScalingConfig withPlayersPerService(int value) {
        return new ScalingConfig(staticServices, minServices, maxServices, value, scaleUpAt, scaleDownBelow, scaleDownAfter,
                cooldown);
    }

    /** A copy with another {@code scale-up-at}. */
    public ScalingConfig withScaleUpAt(int value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, value, scaleDownBelow,
                scaleDownAfter, cooldown);
    }

    /** A copy with another {@code scale-down-below}. */
    public ScalingConfig withScaleDownBelow(int value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, value,
                scaleDownAfter, cooldown);
    }

    /** A copy with another {@code scale-down-after}. */
    public ScalingConfig withScaleDownAfter(TimeSpan value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                value, cooldown);
    }

    /** A copy with another {@code cooldown}. */
    public ScalingConfig withCooldown(TimeSpan value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                scaleDownAfter, value);
    }
}
