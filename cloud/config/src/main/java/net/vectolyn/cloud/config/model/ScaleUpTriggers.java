// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Extra reasons for a task to get one more dynamic service besides the player load, under
 * {@code scaling.scale-up-on} of a task. Every field is optional; a missing one is not checked. The
 * values are averages over the running services of the task, see {@code docs/cloud/scaling.md}.
 *
 * @param cpuAbove CPU use of a service in percent of the machine, 1 to 100: one more service when the
 *                 average is above it
 * @param tpsBelow ticks per second, 1 to 20: one more service when the average is below it
 * @param ramAbove used heap in percent of the maximum heap, 1 to 100: one more service when the
 *                 average is above it
 */
@JsonPropertyOrder({"cpu-above", "tps-below", "ram-above"})
public record ScaleUpTriggers(Integer cpuAbove, Integer tpsBelow, Integer ramAbove) {

    /**
     * Whether no field is set. Not named {@code isEmpty}, which Jackson would write as a field.
     *
     * @return true if nothing is checked
     */
    public boolean noneSet() {
        return cpuAbove == null && tpsBelow == null && ramAbove == null;
    }
}
