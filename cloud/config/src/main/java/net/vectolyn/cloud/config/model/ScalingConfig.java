// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;
import java.util.List;

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
 * @param scaleUpOn         extra reasons for one more dynamic service (CPU, TPS, heap); null if
 *                          there are none (a missing or empty section)
 * @param schedules         time windows with a higher minimum; null if there are none (a missing or
 *                          empty list), see {@link #schedulesOrEmpty()}
 */
@JsonPropertyOrder({"static-services", "min-services", "max-services", "players-per-service", "scale-up-at",
        "scale-down-below", "scale-down-after", "cooldown", "scale-up-on", "schedules"})
public record ScalingConfig(@JsonProperty(required = true) int staticServices, int minServices, int maxServices, int playersPerService, int scaleUpAt,
                            Integer scaleDownBelow, TimeSpan scaleDownAfter, TimeSpan cooldown,
                            ScaleUpTriggers scaleUpOn, List<ScalingSchedule> schedules) {

    /** {@code scale-up-at} of new tasks, in percent. */
    public static final int DEFAULT_SCALE_UP_AT = 75;
    /** {@code scale-down-below} of new tasks and of files without the field, in percent. */
    public static final int DEFAULT_SCALE_DOWN_BELOW = 50;

    /**
     * A missing {@code scale-down-below} becomes {@link #DEFAULT_SCALE_DOWN_BELOW}; an empty
     * {@code scale-up-on} or {@code schedules} becomes null, so it is not written.
     */
    public ScalingConfig {
        scaleDownBelow = scaleDownBelow == null ? DEFAULT_SCALE_DOWN_BELOW : scaleDownBelow;
        scaleUpOn = scaleUpOn == null || scaleUpOn.noneSet() ? null : scaleUpOn;
        schedules = schedules == null || schedules.isEmpty() ? null : List.copyOf(schedules);
    }

    /** Without {@code scale-up-on} and {@code schedules}. */
    public ScalingConfig(int staticServices, int minServices, int maxServices, int playersPerService, int scaleUpAt,
                         Integer scaleDownBelow, TimeSpan scaleDownAfter, TimeSpan cooldown) {
        this(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow, scaleDownAfter,
                cooldown, null, null);
    }

    /**
     * The schedules, an empty list if there are none.
     *
     * @return the schedules
     */
    public List<ScalingSchedule> schedulesOrEmpty() {
        return schedules == null ? List.of() : schedules;
    }

    /**
     * The minimum at a local time: {@code min-services}, raised by every schedule active then, at
     * most {@code max-services}.
     *
     * @param at the date and time in the zone of the node
     * @return the minimum
     */
    public int minServicesAt(LocalDateTime at) {
        int result = minServices;
        for (ScalingSchedule schedule : schedulesOrEmpty()) {
            if (schedule.activeAt(at)) {
                result = Math.max(result, schedule.minServices());
            }
        }
        return Math.min(result, Math.max(minServices, maxServices));
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
                cooldown, scaleUpOn, schedules);
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
                cooldown, scaleUpOn, schedules);
    }

    /** A copy with another {@code scale-up-at}. */
    public ScalingConfig withScaleUpAt(int value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, value, scaleDownBelow,
                scaleDownAfter, cooldown, scaleUpOn, schedules);
    }

    /** A copy with another {@code scale-down-below}. */
    public ScalingConfig withScaleDownBelow(int value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, value,
                scaleDownAfter, cooldown, scaleUpOn, schedules);
    }

    /** A copy with another {@code scale-down-after}. */
    public ScalingConfig withScaleDownAfter(TimeSpan value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                value, cooldown, scaleUpOn, schedules);
    }

    /** A copy with other {@code scale-up-on} triggers; null removes them. */
    public ScalingConfig withScaleUpOn(ScaleUpTriggers value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                scaleDownAfter, cooldown, value, schedules);
    }

    /** A copy with other {@code schedules}; null or empty removes them. */
    public ScalingConfig withSchedules(List<ScalingSchedule> value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                scaleDownAfter, cooldown, scaleUpOn, value);
    }

    /** A copy with another {@code cooldown}. */
    public ScalingConfig withCooldown(TimeSpan value) {
        return new ScalingConfig(staticServices, minServices, maxServices, playersPerService, scaleUpAt, scaleDownBelow,
                scaleDownAfter, value, scaleUpOn, schedules);
    }
}
