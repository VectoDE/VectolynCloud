// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.scaling;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.vectolyn.protocol.ServiceId;

/**
 * One decision of the autoscaler as data, for the API's {@code scaling} events and the monitor's
 * counters. The same decisions are logged as text; see {@code docs/cloud/scaling.md#decisions-as-data}.
 *
 * @param at      when it happened
 * @param task    task name
 * @param group   group of the task, empty if the task is gone
 * @param action  what happened
 * @param reason  why, in lower-case kebab form: a {@link ScalingPlanner.Reason} ({@code min-services},
 *                {@code schedule}, {@code load}, {@code cpu}, {@code tps}, {@code ram},
 *                {@code low-load}, {@code above-max}), a {@link ScalingPlanner.Hold}
 *                ({@code memory-unknown}, {@code memory-budget}, {@code memory-free}, {@code paused}) or
 *                for {@link Action#KEEP} {@code no-target}, {@code timeout} or {@code interrupted}
 * @param service the service, empty for {@link Action#HOLD}
 */
public record ScalingEvent(Instant at, String task, String group, Action action, String reason,
                           Optional<ServiceId> service) {

    /** What happened. */
    public enum Action {
        /** A service was started. Counts as a scale-up. */
        START,
        /** The players of a service are being moved away before it stops. */
        DRAIN,
        /** An emptied service was stopped. Counts as a scale-down. */
        STOP,
        /** An emptied service keeps running: its players could not be moved. */
        KEEP,
        /** The task needs another service but does not get one now. */
        HOLD;

        /**
         * The name on the wire.
         *
         * @return lower case
         */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Checks the values. */
    public ScalingEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(service, "service");
    }

    /**
     * The kebab form of an enum constant: {@code MIN_SERVICES} becomes {@code min-services}.
     *
     * @param constant the constant
     * @return the text
     */
    static String kebab(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
